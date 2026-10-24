package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;
import io.github.dream07code.innovationspace.assessment.*;
import io.github.dream07code.innovationspace.assessment.service.AssessmentJudgeWorker;


import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssessmentSubmissionServiceImpl implements AssessmentSubmissionService {
  private static final Set<String> LANGUAGES =
      Set.of("java", "python", "cpp", "javascript", "go", "rust");
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final AssessmentJudgeWorker judgeWorker;
  private final JsonMapper json = JsonMapper.builder().build();

  public AssessmentSubmissionServiceImpl(
      JdbcTemplate jdbc,
      IdempotencyService idem,
      Clock clock,
      PlatformTransactionManager manager,
      AssessmentJudgeWorker judgeWorker) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.judgeWorker = judgeWorker;
  }

  public IdempotencyService.StoredResponse create(
      long assessmentId, Map<String, Object> request, SessionPrincipal actor, String key) {
    if (!"STUDENT".equals(actor.role()) || actor.memberId() == null) throw forbidden();
    long problemId = validate(request, assessmentId, actor);
    long[] created = new long[1];
    var result =
        idem.execute(
            actor,
            "POST",
            "/api/v1/assessments/" + assessmentId + "/submissions",
            key,
            json.writeValueAsString(request),
            () ->
                tx.execute(
                    status -> {
                      Instant now = clock.instant();
                      String code = (String) request.get("code");
                      jdbc.update(
                          "INSERT INTO assessment_submission(assessment_id,problem_id,member_id,language,code_locator,code_hash,status,version,submitted_at,updated_at)"
                              + " VALUES(?,?,?,?,?,?, 'RECEIVED',1,?,?)",
                          assessmentId,
                          problemId,
                          actor.memberId(),
                          request.get("language"),
                          "controlled-db-code",
                          hash(code),
                          Timestamp.from(now),
                          Timestamp.from(now));
                      long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                      jdbc.update(
                          "INSERT INTO assessment_submission_code(submission_id,code_text,created_at) VALUES(?,?,?)",
                          id,
                          code,
                          Timestamp.from(now));
                      jdbc.update(
                          "UPDATE assessment_submission SET code_locator=? WHERE id=?",
                          "controlled-db-code:" + id,
                          id);
                      jdbc.update(
                          "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
                              + " VALUES(?,'SUBMISSION_CREATED','SUBMISSION',?, 'Submission created','SUCCESS',?)",
                          actor.userId(),
                          id,
                          Timestamp.from(now));
                      created[0] = id;
                      return new IdempotencyService.StoredResponse(
                          201, json.writeValueAsString(ApiResponse.success(response(id))));
                    }));
    if (created[0] > 0) judgeWorker.enqueue(created[0]);
    return result;
  }

  public Map<String, Object> get(long id, SessionPrincipal actor) {
    var row = row(id);
    requireVisible(actor, row);
    var out = response(id);
    if (out.get("judgeResult") instanceof Map<?, ?> judge) {
      @SuppressWarnings("unchecked")
      var result = (Map<String, Object>) judge;
      var sample = sampleCase(id);
      if (sample != null) result.put("sampleCase", sample);
    }
    return out;
  }

  public Map<String, Object> list(
      SessionPrincipal actor,
      int page,
      int size,
      String assessmentValue,
      String problemValue,
      String memberValue,
      String status) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    Long assessmentId = assessmentValue == null ? null : parseId(assessmentValue);
    Long problemId = problemValue == null ? null : parseId(problemValue);
    Long memberId = memberValue == null ? null : parseId(memberValue);
    if (status != null && !Set.of("RECEIVED", "JUDGING", "JUDGED", "FAILED", "NEEDS_REVIEW").contains(status))
      throw bad();
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE 1=1");
    if ("STUDENT".equals(actor.role())) {
      where.append(" AND s.member_id=?");
      args.add(actor.memberId());
    } else if ("DIRECTION_LEADER".equals(actor.role())) {
      if (actor.managedDirectionIds().isEmpty()) where.append(" AND 1=0");
      else {
        where.append(" AND a.direction_id IN (")
            .append(String.join(",", java.util.Collections.nCopies(actor.managedDirectionIds().size(), "?")))
            .append(")");
        args.addAll(actor.managedDirectionIds());
      }
    } else if (!"COLLEGE_ADMIN".equals(actor.role())) throw forbidden();
    if (assessmentId != null) {
      where.append(" AND s.assessment_id=?");
      args.add(assessmentId);
    }
    if (problemId != null) {
      where.append(" AND s.problem_id=?");
      args.add(problemId);
    }
    if (memberId != null) {
      where.append(" AND s.member_id=?");
      args.add(memberId);
    }
    if (status != null) {
      where.append(" AND s.status=?");
      args.add(status);
    }
    String from = " FROM assessment_submission s JOIN assessment a ON a.id=s.assessment_id" + where;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT s.id" + from + " ORDER BY s.submitted_at DESC,s.id DESC LIMIT ? OFFSET ?",
            Long.class,
            append(args, size, (page - 1) * size));
    var items = new ArrayList<Map<String, Object>>();
    for (Long id : ids) items.add(response(id));
    Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  private long validate(Map<String, Object> request, long assessmentId, SessionPrincipal actor) {
    if (request == null || !Set.of("problemId", "language", "code").equals(request.keySet())) throw bad();
    long problemId = parseId(request.get("problemId"));
    String language = request.get("language") instanceof String value ? value : null;
    if (language == null || !LANGUAGES.contains(language)) throw bad();
    if (!(request.get("code") instanceof String code) || code.isBlank() || code.length() > 200000) throw bad();
    var assessments =
        jdbc.queryForList(
            "SELECT * FROM assessment WHERE id=? AND status='PUBLISHED' AND start_at<=? AND end_at>=?",
            assessmentId,
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()));
    if (assessments.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    var assessment = assessments.getFirst();
    if (!"COLLEGE_ADMIN".equals(actor.role())
        && !memberDirection(actor.memberId()).equals(((Number) assessment.get("direction_id")).longValue()))
      throw new ApiException(404, ErrorCode.NOT_FOUND);
    if (!languageList(assessment.get("allowed_languages")).contains(language)) throw bad();
    var problems =
        jdbc.queryForList(
            "SELECT p.* FROM assessment_problem p JOIN assessment_item i ON i.problem_id=p.id"
                + " WHERE i.assessment_id=? AND i.problem_id=? AND i.item_type='PROGRAM'",
            assessmentId,
            problemId);
    if (problems.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    if (!languageList(problems.getFirst().get("allowed_languages")).contains(language)) throw bad();
    return problemId;
  }

  public Map<String, Object> code(long id, SessionPrincipal actor) {
    var row = row(id);
    requireVisible(actor, row);
    var rows =
        jdbc.queryForList(
            "SELECT code_text FROM assessment_submission_code WHERE submission_id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    boolean owner =
        "STUDENT".equals(actor.role())
            && actor.memberId() != null
            && actor.memberId() == ((Number) row.get("member_id")).longValue();
    if (!owner)
      jdbc.update(
          "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
              + " VALUES(?,'SUBMISSION_CODE_VIEWED','SUBMISSION',?,'Submission code viewed','SUCCESS',?)",
          actor.userId(),
          id,
          Timestamp.from(clock.instant()));
    var out = new LinkedHashMap<String, Object>();
    out.put("submissionId", String.valueOf(id));
    out.put("language", row.get("language"));
    out.put("code", String.valueOf(rows.getFirst().get("code_text")));
    return out;
  }

  /**
   * 给学生一个参考测试点：优先从失败用例里按提交号稳定挑选一个，
   * 全部通过时退回随机一个；只返回截断后的输入、实际输出和期望输出。
   */
  private Map<String, Object> sampleCase(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT position,verdict,input_preview,actual_output,expected_output"
                + " FROM oj_test_case_result WHERE submission_id=? ORDER BY position",
            submissionId);
    if (rows.isEmpty()) return null;
    var failed = new ArrayList<Map<String, Object>>();
    for (var row : rows) if (!"ACCEPTED".equals(row.get("verdict"))) failed.add(row);
    var pool = failed.isEmpty() ? rows : failed;
    int index = (int) Math.floorMod(submissionId * 31L + 7, pool.size());
    var row = pool.get(index);
    var out = new LinkedHashMap<String, Object>();
    out.put("position", row.get("position"));
    out.put("verdict", row.get("verdict"));
    out.put("input", row.get("input_preview"));
    out.put("actualOutput", row.get("actual_output"));
    out.put("expectedOutput", row.get("expected_output"));
    return out;
  }

  private Map<String, Object> response(long id) {
    var rows =
        jdbc.queryForList(
            "SELECT s.*,r.verdict,r.passed_test_cases,r.total_test_cases,r.time_ms,r.memory_kb,r.safe_error,r.created_at AS judged_at,"
                + "m.name AS member_name,m.student_no AS member_student_no"
                + " FROM assessment_submission s LEFT JOIN oj_judge_result r ON r.submission_id=s.id"
                + " LEFT JOIN member m ON m.id=s.member_id WHERE s.id=?",
            id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    var row = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("assessmentId", String.valueOf(row.get("assessment_id")));
    out.put("problemId", String.valueOf(row.get("problem_id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("memberName", row.get("member_name"));
    out.put("memberStudentNo", row.get("member_student_no"));
    out.put("language", row.get("language"));
    out.put("status", row.get("status"));
    out.put("codeHash", hex((byte[]) row.get("code_hash")));
    out.put("version", row.get("version"));
    out.put("submittedAt", time(row.get("submitted_at")));
    out.put("judgedAt", row.get("judged_at") == null ? null : time(row.get("judged_at")));
    if (row.get("verdict") != null) {
      var judge = new LinkedHashMap<String, Object>();
      judge.put("verdict", row.get("verdict"));
      judge.put("passedTestCases", row.get("passed_test_cases"));
      judge.put("totalTestCases", row.get("total_test_cases"));
      judge.put("timeMs", row.get("time_ms"));
      judge.put("memoryKb", row.get("memory_kb"));
      judge.put("safeError", row.get("safe_error"));
      judge.put("testCaseResults", testCaseResults(id));
      out.put("judgeResult", judge);
    }
    return out;
  }

  private List<Map<String, Object>> testCaseResults(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT position,verdict,time_ms,memory_kb,safe_error FROM oj_test_case_result"
                + " WHERE submission_id=? ORDER BY position",
            submissionId);
    var results = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var result = new LinkedHashMap<String, Object>();
      result.put("position", row.get("position"));
      result.put("verdict", row.get("verdict"));
      result.put("timeMs", row.get("time_ms"));
      result.put("memoryKb", row.get("memory_kb"));
      result.put("safeError", row.get("safe_error"));
      results.add(result);
    }
    return results;
  }

  private Map<String, Object> row(long id) {
    var rows = jdbc.queryForList("SELECT s.*,a.direction_id FROM assessment_submission s JOIN assessment a ON a.id=s.assessment_id WHERE s.id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private void requireVisible(SessionPrincipal actor, Map<String, Object> row) {
    long memberId = row.get("member_id") == null ? 0 : ((Number) row.get("member_id")).longValue();
    long directionId = ((Number) row.get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if ("STUDENT".equals(actor.role()) && actor.memberId() != null && actor.memberId() == memberId) return;
    if ("DIRECTION_LEADER".equals(actor.role()) && actor.managedDirectionIds().contains(directionId)) return;
    throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private Long memberDirection(Long memberId) {
    if (memberId == null) throw forbidden();
    return jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?", Long.class, memberId);
  }

  @SuppressWarnings("unchecked")
  private static List<String> languageList(Object value) {
    if (!(value instanceof String text)) return List.of();
    try {
      return JsonMapper.builder().build().readValue(text, List.class);
    } catch (RuntimeException error) {
      return List.of();
    }
  }

  private static Object[] append(List<Object> args, Object... extra) {
    Object[] result = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) result[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) result[args.size() + i] = extra[i];
    return result;
  }

  private static long parseId(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) throw bad();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw bad();
    }
  }

  private static byte[] hash(String code) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String hex(byte[] value) {
    return value == null ? null : HexFormat.of().formatHex(value);
  }

  private static String time(Object value) {
    Instant instant =
        value instanceof Timestamp timestamp
            ? timestamp.toInstant()
            : value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC)
                : (Instant) value;
    return instant.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }

  private static ApiException forbidden() {
    return new ApiException(403, ErrorCode.FORBIDDEN);
  }
}
