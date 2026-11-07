package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.assessment.QuizReviewPolicy;
import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.QuizReviewService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 填空题人工阅卷。
 *
 * <p>口径：交卷时的自动分保存在 auto_awarded，人工分一旦写入即以 manual_score 覆盖 awarded；
 * 撤销人工分回退到自动分。总分始终由明细 SUM(awarded) 重算，不直接累加。
 */
@Service
public class QuizReviewServiceImpl implements QuizReviewService {
  private static final int MAX_BATCH = 200;
  private static final Set<String> FILTERS = Set.of("all", "pending", "auto_zero", "manual");

  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public QuizReviewServiceImpl(
      JdbcTemplate jdbc, IdempotencyService idem, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public Map<String, Object> review(
      long assessmentId, long questionId, String filter, int page, int size, SessionPrincipal actor) {
    var assessment = requireManage(assessmentId, actor);
    requireGradable(assessment);
    if (page < 1 || size < 1 || size > 200) throw bad();
    String mode = filter == null || filter.isBlank() ? "all" : filter;
    if (!FILTERS.contains(mode)) throw bad();

    var question = questionItem(assessmentId, questionId);
    int position = ((Number) question.get("position")).intValue();
    String condition =
        switch (mode) {
          case "pending" -> " AND d.sheet_id IS NOT NULL AND d.manual_score IS NULL";
          case "auto_zero" ->
              " AND d.sheet_id IS NOT NULL AND d.manual_score IS NULL AND d.auto_awarded=0";
          case "manual" -> " AND d.manual_score IS NOT NULL";
          default -> "";
        };

    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet s"
                + " LEFT JOIN assessment_answer_sheet_item d"
                + "   ON d.sheet_id=s.id AND d.position=?"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED'"
                + condition,
            Long.class,
            position,
            assessmentId);
    Long submitted =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet WHERE assessment_id=? AND status='SUBMITTED'",
            Long.class,
            assessmentId);
    Long graded =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet s"
                + " JOIN assessment_answer_sheet_item d ON d.sheet_id=s.id AND d.position=?"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED' AND d.manual_score IS NOT NULL",
            Long.class,
            position,
            assessmentId);

    var rows =
        jdbc.queryForList(
            "SELECT s.id AS sheet_id, s.member_id, m.name AS member_name, m.student_no,"
                + " d.sheet_id AS detail_sheet_id, d.answer, d.auto_awarded, d.awarded,"
                + " d.manual_score, d.review_comment, d.reviewed_at, d.version"
                + " FROM assessment_answer_sheet s"
                + " JOIN member m ON m.id=s.member_id"
                + " LEFT JOIN assessment_answer_sheet_item d ON d.sheet_id=s.id AND d.position=?"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED'"
                + condition
                + " ORDER BY m.student_no, s.id LIMIT ? OFFSET ?",
            position,
            assessmentId,
            size,
            (page - 1) * size);

    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) items.add(reviewRow(row));

    var out = new LinkedHashMap<String, Object>();
    var meta = new LinkedHashMap<String, Object>();
    meta.put("id", String.valueOf(assessmentId));
    meta.put("title", assessment.get("title"));
    meta.put("status", assessment.get("status"));
    meta.put("directionId", String.valueOf(assessment.get("direction_id")));
    meta.put("resultsPublished", assessment.get("results_published_at") != null);
    out.put("assessment", meta);
    var questionOut = new LinkedHashMap<String, Object>();
    questionOut.put("position", position);
    questionOut.put("questionId", String.valueOf(questionId));
    questionOut.put("stem", question.get("stem"));
    questionOut.put("score", ((Number) question.get("score")).intValue());
    questionOut.put("referenceAnswers", decode(String.valueOf(question.get("answer"))));
    questionOut.put("analysis", question.get("analysis"));
    out.put("question", questionOut);
    out.put("progress", Map.of("graded", graded, "total", submitted));
    out.put("items", items);
    out.put("page", page);
    out.put("size", size);
    out.put("total", total == null ? 0 : total);
    return out;
  }

  @Override
  public Map<String, Object> summary(long assessmentId, SessionPrincipal actor) {
    var assessment = requireManage(assessmentId, actor);
    int pending = pendingCount(assessmentId);
    Long submitted =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet WHERE assessment_id=? AND status='SUBMITTED'",
            Long.class,
            assessmentId);
    // 题目清单随进度一次下发：阅卷页不必再逐题回查题库（远程网络下 N 次串行请求会拖到分钟级）。
    var questions = fillBlankQuestions(assessmentId);
    Long graded =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet_item d"
                + " JOIN assessment_answer_sheet s ON s.id=d.sheet_id"
                + " JOIN quiz_question q ON q.id=d.question_id"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED' AND q.type='FILL_BLANK'"
                + " AND d.manual_score IS NOT NULL",
            Long.class,
            assessmentId);
    boolean published = assessment.get("results_published_at") != null;
    var out = new LinkedHashMap<String, Object>();
    var meta = new LinkedHashMap<String, Object>();
    meta.put("id", String.valueOf(assessmentId));
    meta.put("title", assessment.get("title"));
    meta.put("status", assessment.get("status"));
    meta.put("resultsPublished", published);
    out.put("assessment", meta);
    out.put("questions", questions);
    out.put("totalItems", (submitted == null ? 0 : submitted.intValue()) * questions.size());
    out.put("gradedItems", graded == null ? 0 : graded.intValue());
    out.put("pendingItems", pending);
    out.put("submittedSheets", submitted == null ? 0 : submitted.intValue());
    out.put("resultsPublished", published);
    out.put(
        "canPublish",
        QuizReviewPolicy.canPublish((String) assessment.get("status"), published, pending));
    out.put("pointsEnabled", Boolean.TRUE.equals(assessment.get("points_enabled")));
    return out;
  }

  @Override
  public int pendingCount(long assessmentId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet_item d"
                + " JOIN assessment_answer_sheet s ON s.id=d.sheet_id"
                + " JOIN assessment_item i ON i.assessment_id=s.assessment_id AND i.position=d.position"
                + " JOIN quiz_question q ON q.id=d.question_id"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED' AND q.type='FILL_BLANK'"
                + " AND d.manual_score IS NULL",
            Integer.class,
            assessmentId);
    return count == null ? 0 : count;
  }

  @Override
  public IdempotencyService.StoredResponse grade(
      long assessmentId, Map<String, Object> request, SessionPrincipal actor, String key) {
    requireManage(assessmentId, actor);
    validateBatch(request);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessments/" + assessmentId + "/quiz-review/scores",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  // 与发布成绩互斥：先锁考核行，避免发布提交后仍能改分。
                  lockAssessment(assessmentId);
                  var assessment = requireManage(assessmentId, actor);
                  requireGradable(assessment);
                  var scores = fillBlankScores(assessmentId);
                  var sheetTotals = new LinkedHashMap<Long, Integer>();
                  var reviewed = new LinkedHashSet<Long>();
                  for (var raw : items(request)) {
                    var entry = asMap(raw);
                    long sheetId = idField(entry, "sheetId");
                    int position = intField(entry, "position");
                    int score = intField(entry, "score");
                    long version = longField(entry, "version");
                    String comment = comment(entry.get("comment"));
                    Integer max = scores.get(position);
                    if (max == null) throw bad();
                    if (score < 0 || score > max) throw bad();
                    requireSubmittedSheet(assessmentId, sheetId);
                    var detail = detailRow(sheetId, position);
                    if (detail == null) throw bad();
                    long currentVersion = ((Number) detail.get("version")).longValue();
                    if (currentVersion != version) throw conflict();
                    int updated =
                        jdbc.update(
                            "UPDATE assessment_answer_sheet_item SET manual_score=?,awarded=?,"
                                + " review_comment=?,reviewed_by=?,reviewed_at=?,version=version+1"
                                + " WHERE sheet_id=? AND position=? AND version=?",
                            score,
                            score,
                            comment,
                            actor.userId(),
                            Timestamp.from(clock.instant()),
                            sheetId,
                            position,
                            version);
                    if (updated == 0) throw conflict();
                    audit(
                        actor,
                        "QUIZ_ITEM_MANUAL_GRADED",
                        sheetId,
                        Map.of(
                            "position", position,
                            "autoAwarded", detail.get("auto_awarded"),
                            "beforeAwarded", detail.get("awarded"),
                            "manualScore", score,
                            "version", version + 1),
                        "Grade fill-blank item " + position + " of sheet " + sheetId);
                    reviewed.add(sheetId);
                    sheetTotals.put(sheetId, recomputeTotal(sheetId));
                  }
                  var out = new LinkedHashMap<String, Object>();
                  out.put("updated", reviewed.size());
                  var sheets = new ArrayList<Map<String, Object>>();
                  for (var entry : sheetTotals.entrySet()) {
                    sheets.add(
                        Map.of(
                            "sheetId", String.valueOf(entry.getKey()),
                            "totalScore", entry.getValue()));
                  }
                  out.put("sheets", sheets);
                  return new IdempotencyService.StoredResponse(
                      200, json.writeValueAsString(ApiResponse.success(out)));
                }));
  }

  @Override
  public IdempotencyService.StoredResponse revoke(
      long assessmentId,
      long sheetId,
      int position,
      Map<String, Object> request,
      SessionPrincipal actor,
      String key) {
    requireManage(assessmentId, actor);
    if (request == null || request.size() != 1 || !request.containsKey("version")) throw bad();
    long version = longField(request, "version");
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessments/" + assessmentId + "/quiz-review/scores/" + sheetId + "/" + position
            + "/revoke",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  lockAssessment(assessmentId);
                  var assessment = requireManage(assessmentId, actor);
                  requireGradable(assessment);
                  if (!fillBlankScores(assessmentId).containsKey(position)) throw bad();
                  requireSubmittedSheet(assessmentId, sheetId);
                  var detail = detailRow(sheetId, position);
                  if (detail == null || detail.get("manual_score") == null) throw bad();
                  if (((Number) detail.get("version")).longValue() != version) throw conflict();
                  int updated =
                      jdbc.update(
                          "UPDATE assessment_answer_sheet_item SET manual_score=NULL,"
                              + " awarded=auto_awarded,review_comment=NULL,reviewed_by=NULL,"
                              + " reviewed_at=NULL,version=version+1"
                              + " WHERE sheet_id=? AND position=? AND version=?",
                          sheetId,
                          position,
                          version);
                  if (updated == 0) throw conflict();
                  int total = recomputeTotal(sheetId);
                  audit(
                      actor,
                      "QUIZ_ITEM_MANUAL_REVOKED",
                      sheetId,
                      Map.of("position", position, "version", version + 1),
                      "Revoke manual score of item " + position + " on sheet " + sheetId);
                  return new IdempotencyService.StoredResponse(
                      200,
                      json.writeValueAsString(
                          ApiResponse.success(
                              Map.of(
                                  "sheetId", String.valueOf(sheetId),
                                  "position", position,
                                  "totalScore", total))));
                }));
  }

  // -- 查询辅助 --------------------------------------------------------

  private void lockAssessment(long assessmentId) {
    var rows = jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE", assessmentId);
    if (rows.isEmpty()) throw notFound();
  }

  private Map<String, Object> requireManage(long assessmentId, SessionPrincipal actor) {    var rows =
        jdbc.queryForList(
            "SELECT id,title,direction_id,status,points_enabled,results_published_at,version"
                + " FROM assessment WHERE id=?",
            assessmentId);
    if (rows.isEmpty()) throw notFound();
    var row = rows.getFirst();
    if ("COLLEGE_ADMIN".equals(actor.role())) return row;
    if ("DIRECTION_LEADER".equals(actor.role())
        && actor
            .managedDirectionIds()
            .contains(((Number) row.get("direction_id")).longValue())) return row;
    throw notFound();
  }

  private void requireGradable(Map<String, Object> assessment) {
    if (!QuizReviewPolicy.canGrade(
        (String) assessment.get("status"), assessment.get("results_published_at") != null))
      throw new ApiException(409, ErrorCode.INVALID_STATE);
  }

  private Map<String, Object> questionItem(long assessmentId, long questionId) {
    var rows =
        jdbc.queryForList(
            "SELECT i.position,i.score,q.id AS question_id,q.stem,q.answer,q.analysis,q.type"
                + " FROM assessment_item i JOIN quiz_question q ON q.id=i.question_id"
                + " WHERE i.assessment_id=? AND i.item_type='QUIZ' AND q.id=?",
            assessmentId,
            questionId);
    if (rows.isEmpty()) throw notFound();
    var row = rows.getFirst();
    if (!"FILL_BLANK".equals(row.get("type"))) throw bad();
    return row;
  }

  private Map<String, Object> reviewRow(Map<String, Object> row) {
    boolean answered = row.get("detail_sheet_id") != null;
    var out = new LinkedHashMap<String, Object>();
    out.put("sheetId", String.valueOf(row.get("sheet_id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("memberName", row.get("member_name"));
    out.put("studentNo", row.get("student_no"));
    out.put("answer", answered ? decode(String.valueOf(row.get("answer"))) : null);
    out.put("answered", answered);
    out.put("autoAwarded", answered ? ((Number) row.get("auto_awarded")).intValue() : 0);
    out.put("awarded", answered ? ((Number) row.get("awarded")).intValue() : 0);
    out.put("manualScore", row.get("manual_score"));
    out.put("reviewed", row.get("manual_score") != null);
    out.put("comment", row.get("review_comment"));
    out.put("version", answered ? ((Number) row.get("version")).longValue() : null);
    out.put("reviewedAt", time(row.get("reviewed_at")));
    return out;
  }

  private Map<String, Object> detailRow(long sheetId, int position) {
    var rows =
        jdbc.queryForList(
            "SELECT sheet_id,position,auto_awarded,awarded,manual_score,version"
                + " FROM assessment_answer_sheet_item WHERE sheet_id=? AND position=?",
            sheetId,
            position);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void requireSubmittedSheet(long assessmentId, long sheetId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_answer_sheet"
                + " WHERE id=? AND assessment_id=? AND status='SUBMITTED'",
            Integer.class,
            sheetId,
            assessmentId);
    if (count == null || count == 0) throw notFound();
  }

  private int recomputeTotal(long sheetId) {
    jdbc.queryForList("SELECT id FROM assessment_answer_sheet WHERE id=? FOR UPDATE", sheetId);
    jdbc.update(
        "UPDATE assessment_answer_sheet s SET total_score=("
            + " SELECT COALESCE(SUM(awarded),0) FROM assessment_answer_sheet_item WHERE sheet_id=s.id)"
            + " WHERE s.id=?",
        sheetId);
    Integer total =
        jdbc.queryForObject(
            "SELECT total_score FROM assessment_answer_sheet WHERE id=?", Integer.class, sheetId);
    return total == null ? 0 : total;
  }

  private Map<Integer, Integer> fillBlankScores(long assessmentId) {
    var map = new LinkedHashMap<Integer, Integer>();
    for (var row : fillBlankItems(assessmentId))
      map.put(((Number) row.get("position")).intValue(), ((Number) row.get("score")).intValue());
    return map;
  }

  private List<Map<String, Object>> fillBlankItems(long assessmentId) {
    return jdbc.queryForList(
        "SELECT i.position,i.score FROM assessment_item i JOIN quiz_question q ON q.id=i.question_id"
            + " WHERE i.assessment_id=? AND i.item_type='QUIZ' AND q.type='FILL_BLANK'"
            + " ORDER BY i.position",
        assessmentId);
  }

  /** 本场全部填空题（含完整题干与参考答案），供阅卷页选择题目与展示原题。 */
  private List<Map<String, Object>> fillBlankQuestions(long assessmentId) {
    var rows =
        jdbc.queryForList(
            "SELECT i.position,i.score,q.id AS question_id,q.stem,q.answer,q.analysis"
                + " FROM assessment_item i JOIN quiz_question q ON q.id=i.question_id"
                + " WHERE i.assessment_id=? AND i.item_type='QUIZ' AND q.type='FILL_BLANK'"
                + " ORDER BY i.position",
            assessmentId);
    var out = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var item = new LinkedHashMap<String, Object>();
      item.put("position", ((Number) row.get("position")).intValue());
      item.put("questionId", String.valueOf(row.get("question_id")));
      item.put("stem", row.get("stem"));
      item.put("score", ((Number) row.get("score")).intValue());
      Object answer = row.get("answer");
      item.put("referenceAnswers", answer == null ? List.of() : decode(String.valueOf(answer)));
      item.put("analysis", row.get("analysis"));
      out.add(item);
    }
    return out;
  }

  // -- 请求校验 --------------------------------------------------------

  private static void validateBatch(Map<String, Object> request) {
    if (request == null || request.size() != 1 || !(request.get("items") instanceof List<?> items))
      throw bad();
    if (items.isEmpty() || items.size() > MAX_BATCH) throw bad();
  }

  private static List<?> items(Map<String, Object> request) {
    return (List<?>) request.get("items");
  }

  private static Map<?, ?> asMap(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) throw bad();
    return map;
  }

  private static long idField(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) throw bad();
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException error) {
      throw bad();
    }
  }

  private static int intField(Map<?, ?> map, String key) {
    long value = longField(map, key);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw bad();
    return (int) value;
  }

  private static long longField(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) return number.longValue();
    if (value instanceof String text && text.matches("-?\\d+")) return Long.parseLong(text);
    throw bad();
  }

  private static String comment(Object raw) {
    if (raw == null) return null;
    if (!(raw instanceof String text)) throw bad();
    String trimmed = text.trim();
    if (trimmed.length() > 500) throw bad();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private void audit(
      SessionPrincipal actor,
      String action,
      long sheetId,
      Map<String, Object> detail,
      String summary) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,detail,"
            + " summary,result,occurred_at) VALUES(?,?,'ASSESSMENT_ANSWER_SHEET_ITEM',?,?,?,'SUCCESS',?)",
        actor.userId(),
        action,
        sheetId,
        json.writeValueAsString(detail),
        summary,
        Timestamp.from(clock.instant()));
  }

  private Object decode(String raw) {
    if (raw == null || "null".equals(raw)) return null;
    return json.readValue(raw, Object.class);
  }

  private String time(Object value) {
    if (value == null) return null;
    Timestamp timestamp =
        value instanceof Timestamp t
            ? t
            : Timestamp.valueOf(((java.time.LocalDateTime) value));
    return OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.ofHours(8)).toString();
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }

  private static ApiException notFound() {
    return new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private static ApiException conflict() {
    return new ApiException(409, ErrorCode.VERSION_CONFLICT);
  }
}
