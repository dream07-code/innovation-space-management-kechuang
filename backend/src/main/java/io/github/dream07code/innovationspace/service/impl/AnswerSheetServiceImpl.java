package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.assessment.QuizGrader;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AnswerSheetService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 客观题答题卡服务。
 *
 * <p>安全口径：paper 响应绝不 select quiz_question 的 answer/analysis 列；判分只在
 * 服务端交卷时进行，不提供逐题试判接口；学生的对错明细在考核 CLOSED/ARCHIVED 前
 * 由 mySheet 剥离，防止考试窗口内泄题。
 *
 * <p>窗口与状态机：保存/交卷都要求 start_at ≤ now ≤ end_at（409）；交卷走
 * DRAFT→SUBMITTED 单向 CAS，重放由 Idempotency-Key 承担。
 */
@Service
public class AnswerSheetServiceImpl implements AnswerSheetService {
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public AnswerSheetServiceImpl(
      JdbcTemplate jdbc, IdempotencyService idem, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public Map<String, Object> paper(long assessmentId, SessionPrincipal actor) {
    var assessment = requireReadable(assessmentId, actor);
    var out = new LinkedHashMap<String, Object>();
    out.put("assessmentId", String.valueOf(assessmentId));
    out.put("title", assessment.get("title"));
    out.put("startAt", time(assessment.get("start_at")));
    out.put("endAt", time(assessment.get("end_at")));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : itemRows(assessmentId)) {
      var item = new LinkedHashMap<String, Object>();
      item.put("position", ((Number) row.get("position")).intValue());
      item.put("type", row.get("item_type"));
      item.put("refId", String.valueOf(row.get("problem_id") != null ? row.get("problem_id") : row.get("question_id")));
      item.put("score", ((Number) row.get("score")).intValue());
      if ("QUIZ".equals(row.get("item_type"))) {
        item.put("quizType", row.get("quiz_type"));
        item.put("stem", row.get("stem"));
        item.put("options", decode(String.valueOf(row.get("options"))));
        item.put("tags", decode(String.valueOf(row.get("tags"))));
      } else {
        item.put("problemTitle", row.get("title"));
        item.put("difficulty", row.get("difficulty"));
      }
      items.add(item);
    }
    out.put("items", items);
    out.put("mySheet", actor.memberId() == null ? null : mySheetSummary(assessmentId, actor.memberId()));
    return out;
  }

  @Override
  public Map<String, Object> saveDraft(
      long assessmentId, Map<String, Object> request, SessionPrincipal actor) {
    var assessment = requireReadable(assessmentId, actor);
    long memberId = requireMember(actor);
    requireWindowOpen(assessment);
    Map<Integer, Object> answers = validateAnswers(assessmentId, request);
    var sheet = sheetRow(assessmentId, memberId);
    if (sheet != null && "SUBMITTED".equals(sheet.get("status")))
      throw new ApiException(409, ErrorCode.INVALID_STATE);
    Timestamp now = Timestamp.from(clock.instant());
    if (sheet == null) {
      jdbc.update(
          "INSERT INTO assessment_answer_sheet(assessment_id,member_id,answers,status,created_at,updated_at)"
              + " VALUES(?,?,?,'DRAFT',?,?)",
          assessmentId,
          memberId,
          json.writeValueAsString(answers),
          now,
          now);
    } else {
      jdbc.update(
          "UPDATE assessment_answer_sheet SET answers=?,updated_at=? WHERE id=?",
          json.writeValueAsString(answers),
          now,
          sheet.get("id"));
    }
    return mySheetSummary(assessmentId, memberId);
  }

  @Override
  public IdempotencyService.StoredResponse submit(
      long assessmentId, SessionPrincipal actor, String key) {
    requireReadable(assessmentId, actor);
    long memberId = requireMember(actor);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit",
        key,
        "{}",
        () ->
            tx.execute(
                status -> {
                  var assessment = requireReadable(assessmentId, actor);
                  requireWindowOpen(assessment);
                  var sheet = sheetRow(assessmentId, memberId);
                  Timestamp now = Timestamp.from(clock.instant());
                  if (sheet == null) {
                    jdbc.update(
                        "INSERT INTO assessment_answer_sheet(assessment_id,member_id,answers,status,"
                            + "created_at,updated_at) VALUES(?,?,NULL,'DRAFT',?,?)",
                        assessmentId,
                        memberId,
                        now,
                        now);
                    sheet = sheetRow(assessmentId, memberId);
                  }
                  if (!"DRAFT".equals(sheet.get("status")))
                    throw new ApiException(409, ErrorCode.INVALID_STATE);
                  gradeAndStore(assessmentId, memberId, actor);
                  return new IdempotencyService.StoredResponse(
                      200,
                      json.writeValueAsString(
                          ApiResponse.success(mySheetDetail(assessmentId, memberId, true))));
                }));
  }

  @Override
  public Map<String, Object> mySheet(long assessmentId, SessionPrincipal actor) {
    requireReadable(assessmentId, actor, true);
    long memberId = requireMember(actor);
    var assessment = row(assessmentId);
    boolean revealed =
        "CLOSED".equals(assessment.get("status")) || "ARCHIVED".equals(assessment.get("status"));
    return mySheetDetail(assessmentId, memberId, revealed);
  }

  @Override
  public Map<String, Object> sheets(
      long assessmentId, SessionPrincipal actor, int page, int size, Long memberId) {
    requireReadable(assessmentId, actor, true);
    if (page < 1 || size < 1 || size > 200) throw bad();
    if ("STUDENT".equals(actor.role())) {
      if (memberId == null || memberId != actor.memberId())
        throw new ApiException(404, ErrorCode.NOT_FOUND);
      // 学生查自己：沿用 mySheet 的揭示口径。
      return Map.of("items", List.of(mySheetDetail(assessmentId, memberId, revealed(assessmentId))),
          "page", 1, "size", 1, "total", 1);
    }
    var args = new ArrayList<Object>();
    args.add(assessmentId);
    var filter = new StringBuilder("");
    if (memberId != null) {
      filter.append(" AND s.member_id=?");
      args.add(memberId);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet s WHERE s.assessment_id=?" + filter,
            Long.class,
            args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var rows =
        jdbc.queryForList(
            "SELECT s.*, m.name AS member_name FROM assessment_answer_sheet s"
                + " JOIN member m ON m.id=s.member_id WHERE s.assessment_id=?" + filter
                + " ORDER BY s.member_id LIMIT ? OFFSET ?",
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var out = summary(row);
      if (memberId != null) {
        out.put("items", detailItems(((Number) row.get("id")).longValue()));
      }
      items.add(out);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("items", items);
    out.put("page", page);
    out.put("size", size);
    out.put("total", total == null ? 0 : total);
    return out;
  }

  // -- 判分 ------------------------------------------------------------

  /** 交卷判分：QuizGrader 纯函数 → 明细行（仅已作答）+ 总分一次落库。 */
  private void gradeAndStore(long assessmentId, long memberId, SessionPrincipal actor) {
    var quizItems = quizItemRows(assessmentId);
    Map<Integer, Object> answers = answersOf(sheetRow(assessmentId, memberId));
    Map<Long, QuizGrader.QuestionSpec> questions = new LinkedHashMap<>();
    for (var item : quizItems) {
      long questionId = ((Number) item.get("question_id")).longValue();
      questions.put(
          questionId,
          new QuizGrader.QuestionSpec(
              (String) item.get("quiz_type"),
              (List<String>) decode(String.valueOf(item.get("options"))),
              decode(String.valueOf(item.get("answer")))));
    }
    List<QuizGrader.ItemSpec> specs = new ArrayList<>();
    for (var item : quizItems) {
      specs.add(
          new QuizGrader.ItemSpec(
              ((Number) item.get("position")).intValue(),
              ((Number) item.get("question_id")).longValue(),
              ((Number) item.get("score")).intValue()));
    }
    List<QuizGrader.Graded> graded = QuizGrader.grade(specs, questions, answers);
    long sheetId =
        ((Number) sheetRow(assessmentId, memberId).get("id")).longValue();
    Timestamp now = Timestamp.from(clock.instant());
    int total = 0;
    for (QuizGrader.Graded g : graded) {
      Object answer = answers.get(g.position());
      if (answer == null) continue; // 未作答不落明细行，按 0 分口径
      total += g.awarded();
      jdbc.update(
          "INSERT INTO assessment_answer_sheet_item(sheet_id,position,question_id,answer,correct,awarded)"
              + " VALUES(?,?,?,?,?,?)",
          sheetId,
          g.position(),
          g.questionId(),
          json.writeValueAsString(answer),
          g.correct(),
          g.awarded());
    }
    jdbc.update(
        "UPDATE assessment_answer_sheet SET status='SUBMITTED',submitted_at=?,total_score=?,"
            + "graded_at=?,updated_at=? WHERE id=? AND status='DRAFT'",
        now,
        total,
        now,
        now,
        sheetId);
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
            + " VALUES(?, 'QUIZ_SHEET_SUBMITTED', 'ASSESSMENT_ANSWER_SHEET', ?, ?, 'SUCCESS', ?)",
        actor.userId(),
        assessmentId,
        "Submitted and graded answer sheet",
        now);
  }

  // -- 校验 ------------------------------------------------------------

  private Map<Integer, Object> validateAnswers(long assessmentId, Map<String, Object> request) {
    if (request == null || !(request.get("answers") instanceof Map<?, ?> raw)) throw bad();
    var quizItems = quizItemRows(assessmentId);
    var byPosition = new LinkedHashMap<Integer, Map<String, Object>>();
    for (var item : quizItems)
      byPosition.put(((Number) item.get("position")).intValue(), item);
    Map<Integer, Object> out = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
      int position;
      try {
        position = Integer.parseInt(String.valueOf(entry.getKey()));
      } catch (NumberFormatException error) {
        throw bad();
      }
      var item = byPosition.get(position);
      if (item == null) throw bad();
      Object value = entry.getValue();
      switch ((String) item.get("quiz_type")) {
        case "SINGLE_CHOICE" -> {
          if (!(value instanceof String letter) || letterIndex(letter, item) < 0) throw bad();
        }
        case "MULTI_CHOICE" -> {
          if (!(value instanceof List<?> letters) || letters.isEmpty()) throw bad();
          var seen = new java.util.HashSet<String>();
          for (var one : letters) {
            if (!(one instanceof String letter) || letterIndex(letter, item) < 0 || !seen.add(letter))
              throw bad();
          }
        }
        case "TRUE_FALSE" -> {
          if (!(value instanceof Boolean)) throw bad();
        }
        case "FILL_BLANK" -> {
          if (!(value instanceof String text) || text.isBlank() || text.length() > 500) throw bad();
        }
        default -> throw bad();
      }
      out.put(position, value);
    }
    return out;
  }

  private static int letterIndex(String letter, Map<String, Object> item) {
    if (letter == null || letter.length() != 1) return -1;
    var options = (List<?>) decodeStatic(String.valueOf(item.get("options")));
    int index = letter.charAt(0) - 'A';
    return index >= 0 && index < (options == null ? 0 : options.size()) ? index : -1;
  }

  private static Object decodeStatic(String raw) {
    if (raw == null || "null".equals(raw)) return null;
    return JsonMapper.builder().build().readValue(raw, Object.class);
  }

  private Object decode(String raw) {
    return decodeStatic(raw);
  }

  private void requireWindowOpen(Map<String, Object> assessment) {
    Instant now = clock.instant();
    Instant start = instant(assessment.get("start_at"));
    Instant end = instant(assessment.get("end_at"));
    if (now.isBefore(start) || now.isAfter(end))
      throw new ApiException(409, ErrorCode.INVALID_STATE);
  }

  /** JDBC 对 DATETIME 可能给 Timestamp 或 LocalDateTime，统一转 Instant。 */
  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
    if (value instanceof Instant instant) return instant;
    throw new IllegalStateException("Unsupported datetime type: " + value.getClass());
  }

  private long requireMember(SessionPrincipal actor) {
    if (actor.memberId() == null) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return actor.memberId();
  }

  private Map<String, Object> requireReadable(long assessmentId, SessionPrincipal actor) {
    return requireReadable(assessmentId, actor, false);
  }

  /**
   * allowFinished：成绩单场景学生允许读 PUBLISHED/CLOSED/ARCHIVED（关闭后要看自己成绩），
   * 试卷与作答仍限 PUBLISHED。
   */
  private Map<String, Object> requireReadable(
      long assessmentId, SessionPrincipal actor, boolean allowFinished) {
    var row = row(assessmentId);
    long directionId = ((Number) row.get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return row;
    if ("DIRECTION_LEADER".equals(actor.role())
        && actor.managedDirectionIds().contains(directionId)) return row;
    if ("STUDENT".equals(actor.role())
        && (allowFinished || "PUBLISHED".equals(row.get("status")))
        && !"DRAFT".equals(row.get("status"))
        && directionId == memberDirection(actor.memberId())) return row;
    throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private long memberDirection(Long memberId) {
    if (memberId == null) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return jdbc.queryForObject(
        "SELECT direction_id FROM member WHERE id=?", Long.class, memberId);
  }

  private Map<String, Object> row(long id) {
    var rows = jdbc.queryForList("SELECT * FROM assessment WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private boolean revealed(long assessmentId) {
    var status = jdbc.queryForObject("SELECT status FROM assessment WHERE id=?", String.class, assessmentId);
    return "CLOSED".equals(status) || "ARCHIVED".equals(status);
  }

  // -- 数据 ------------------------------------------------------------

  private List<Map<String, Object>> itemRows(long assessmentId) {
    return jdbc.queryForList(
        "SELECT i.position,i.item_type,i.problem_id,i.question_id,i.score,"
            + " q.type AS quiz_type,q.stem,q.options,q.tags,q.analysis,"
            + " p.title,p.difficulty"
            + " FROM assessment_item i"
            + " LEFT JOIN quiz_question q ON q.id=i.question_id"
            + " LEFT JOIN assessment_problem p ON p.id=i.problem_id"
            + " WHERE i.assessment_id=? ORDER BY i.position",
        assessmentId);
  }

  private List<Map<String, Object>> quizItemRows(long assessmentId) {
    return jdbc.queryForList(
        "SELECT i.position,i.question_id,i.score,q.type AS quiz_type,q.options,q.answer"
            + " FROM assessment_item i JOIN quiz_question q ON q.id=i.question_id"
            + " WHERE i.assessment_id=? AND i.item_type='QUIZ' ORDER BY i.position",
        assessmentId);
  }

  private Map<String, Object> sheetRow(long assessmentId, long memberId) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM assessment_answer_sheet WHERE assessment_id=? AND member_id=?",
            assessmentId,
            memberId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @SuppressWarnings("unchecked")
  private Map<Integer, Object> answersOf(Map<String, Object> sheet) {
    if (sheet == null || sheet.get("answers") == null) return Map.of();
    var raw = (Map<String, Object>) decode(String.valueOf(sheet.get("answers")));
    var out = new LinkedHashMap<Integer, Object>();
    for (var entry : raw.entrySet()) {
      try {
        out.put(Integer.parseInt(entry.getKey()), entry.getValue());
      } catch (NumberFormatException ignored) {
        // 脏键直接忽略，判分按未答处理。
      }
    }
    return out;
  }

  // -- 装配 ------------------------------------------------------------

  private Map<String, Object> mySheetSummary(long assessmentId, long memberId) {
    var sheet = sheetRow(assessmentId, memberId);
    var out = new LinkedHashMap<String, Object>();
    if (sheet == null) {
      out.put("status", "DRAFT");
      out.put("answers", null);
      return out;
    }
    out.put("status", sheet.get("status"));
    out.put("answers", answersOf(sheet));
    return out;
  }

  private Map<String, Object> mySheetDetail(long assessmentId, long memberId, boolean revealed) {
    var sheet = sheetRow(assessmentId, memberId);
    var out = new LinkedHashMap<String, Object>();
    if (sheet == null) {
      out.put("status", "DRAFT");
      out.put("answeredCount", 0);
      out.put("totalScore", null);
      out.put("submittedAt", null);
      out.put("items", null);
      return out;
    }
    out.put("status", sheet.get("status"));
    out.put("answeredCount", answersOf(sheet).size());
    out.put("totalScore", revealed ? sheet.get("total_score") : null);
    out.put("submittedAt", revealed ? time(sheet.get("submitted_at")) : null);
    out.put("items", revealed ? detailItems(((Number) sheet.get("id")).longValue()) : null);
    return out;
  }

  private List<Map<String, Object>> detailItems(long sheetId) {
    var rows =
        jdbc.queryForList(
            "SELECT d.position,d.answer,d.correct,d.awarded,q.answer AS correct_answer,q.analysis"
                + " FROM assessment_answer_sheet_item d JOIN quiz_question q ON q.id=d.question_id"
                + " WHERE d.sheet_id=? ORDER BY d.position",
            sheetId);
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var item = new LinkedHashMap<String, Object>();
      item.put("position", ((Number) row.get("position")).intValue());
      item.put("answer", decode(String.valueOf(row.get("answer"))));
      item.put("correct", ((Number) row.get("correct")).intValue() == 1);
      item.put("awarded", ((Number) row.get("awarded")).intValue());
      item.put("correctAnswer", decode(String.valueOf(row.get("correct_answer"))));
      item.put("analysis", row.get("analysis"));
      items.add(item);
    }
    return items;
  }

  private Map<String, Object> summary(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("memberName", row.get("member_name"));
    out.put("status", row.get("status"));
    out.put("answeredCount", answersOf(row).size());
    out.put("totalScore", row.get("total_score"));
    out.put("submittedAt", time(row.get("submitted_at")));
    return out;
  }

  private String time(Object value) {
    if (value == null) return null;
    return instant(value).atOffset(java.time.ZoneOffset.ofHours(8)).toString();
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
