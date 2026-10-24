package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import io.github.dream07code.innovationspace.service.AssessmentResultService;
import io.github.dream07code.innovationspace.assessment.*;


import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssessmentResultServiceImpl implements AssessmentResultService {
  private final JdbcTemplate jdbc;
  private final AssessmentEvaluationService evaluations;

  public AssessmentResultServiceImpl(JdbcTemplate jdbc, AssessmentEvaluationService evaluations) {
    this.jdbc = jdbc;
    this.evaluations = evaluations;
  }

  public Map<String, Object> list(
      long assessmentId, SessionPrincipal actor, int page, int size, Long memberId) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    requireReadable(assessmentId, actor);

    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE s.assessment_id=?");
    args.add(assessmentId);
    if ("STUDENT".equals(actor.role())) {
      where.append(" AND s.member_id=?");
      args.add(actor.memberId());
    } else if (memberId != null) {
      // 管理员/负责人按成员看明细时使用
      where.append(" AND s.member_id=?");
      args.add(memberId);
    }
    String from =
        " FROM assessment_result r JOIN assessment_submission s ON s.id=r.submission_id"
            + where;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT r.submission_id" + from + " ORDER BY s.submitted_at DESC,s.id DESC LIMIT ? OFFSET ?",
            Long.class,
            append(args, size, (page - 1) * size));
    var items = new ArrayList<Map<String, Object>>();
    for (Long id : ids) items.add(result(id));
    Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  @Override
  public Map<String, Object> memberScores(
      long assessmentId, SessionPrincipal actor, int page, int size) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    long directionId = requireReadable(assessmentId, actor);
    Long problemCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_item WHERE assessment_id=?",
            Long.class,
            assessmentId);
    int problems = problemCount == null ? 0 : problemCount.intValue();

    var filterArgs = new ArrayList<Object>();
    filterArgs.add(directionId);
    var filter = new StringBuilder(" WHERE m.direction_id=? AND m.membership_status='ACTIVE'");
    if ("STUDENT".equals(actor.role())) {
      filter.append(" AND m.id=?");
      filterArgs.add(actor.memberId());
    }

    // 同一成员同一题目可能多次提交：只取最后一次（提交时间最大，同刻取 id 最大），
    // 与结果页「反复提交可对照修改」的口径一致。
    String sql =
        "WITH latest AS ("
            + " SELECT s.id AS submission_id,s.member_id,s.problem_id,"
            + " ROW_NUMBER() OVER (PARTITION BY s.member_id,s.problem_id"
            + " ORDER BY s.submitted_at DESC,s.id DESC) AS rn"
            + " FROM assessment_submission s WHERE s.assessment_id=?"
            + ") SELECT m.id AS member_id,m.name AS member_name,m.student_no AS student_no,"
            + " COUNT(l.submission_id) AS answered,"
            + " COALESCE(SUM(CASE WHEN r.result_status='COMPLETE' THEN 1 ELSE 0 END),0) AS completed,"
            + " COALESCE(SUM(r.final_score),0) AS total_score,"
            + " MAX(s.submitted_at) AS last_submitted_at"
            + " FROM member m"
            + " LEFT JOIN latest l ON l.member_id=m.id AND l.rn=1"
            + " LEFT JOIN assessment_submission s ON s.id=l.submission_id"
            + " LEFT JOIN assessment_result r ON r.submission_id=l.submission_id"
            + filter
            + " GROUP BY m.id,m.name,m.student_no"
            + " ORDER BY total_score DESC,student_no ASC,m.id ASC LIMIT ? OFFSET ?";
    var args = new ArrayList<Object>();
    args.add(assessmentId);
    args.addAll(filterArgs);
    var rows = jdbc.queryForList(sql, append(args, size, (page - 1) * size));

    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      int answered = number(row.get("answered"));
      int completed = number(row.get("completed"));
      int totalScore = number(row.get("total_score"));
      var out = new LinkedHashMap<String, Object>();
      out.put("memberId", String.valueOf(row.get("member_id")));
      out.put("memberName", row.get("member_name"));
      out.put("memberStudentNo", row.get("student_no"));
      out.put("problemCount", problems);
      out.put("answeredCount", answered);
      out.put("completedCount", completed);
      out.put("pendingCount", Math.max(0, answered - completed));
      out.put("totalScore", totalScore);
      out.put("maxScore", problems * 100);
      // 平均分按题目总数算：少做题的人不会因为分母变小而虚高
      out.put(
          "averageScore",
          problems == 0 ? 0.0 : Math.round(totalScore * 10.0 / problems) / 10.0);
      out.put(
          "lastSubmittedAt",
          row.get("last_submitted_at") == null ? null : time(row.get("last_submitted_at")));
      items.add(out);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM member m" + filter, Long.class, filterArgs.toArray());
    return Map.of("items", items, "page", page, "size", size, "total", total == null ? 0L : total);
  }

  /** 校验当前账号能看这场考核的成绩，返回考核所属方向。 */
  private long requireReadable(long assessmentId, SessionPrincipal actor) {
    var assessments =
        jdbc.queryForList("SELECT direction_id FROM assessment WHERE id=?", assessmentId);
    if (assessments.isEmpty()) throw notFound();
    long directionId = ((Number) assessments.getFirst().get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return directionId;
    if ("DIRECTION_LEADER".equals(actor.role())) {
      if (!actor.managedDirectionIds().contains(directionId)) throw notFound();
      return directionId;
    }
    if ("STUDENT".equals(actor.role()) && actor.memberId() != null) return directionId;
    throw forbidden();
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private Map<String, Object> result(long submissionId) {
    evaluations.refreshResult(submissionId);
    var rows =
        jdbc.queryForList(
            "SELECT r.*,s.assessment_id,s.problem_id,s.member_id,m.name AS member_name,m.student_no AS member_student_no"
                + " FROM assessment_result r"
                + " JOIN assessment_submission s ON s.id=r.submission_id"
                + " LEFT JOIN member m ON m.id=s.member_id"
                + " WHERE r.submission_id=?",
            submissionId);
    if (rows.isEmpty()) throw notFound();
    var row = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put("assessmentId", String.valueOf(row.get("assessment_id")));
    out.put("submissionId", String.valueOf(row.get("submission_id")));
    out.put("problemId", String.valueOf(row.get("problem_id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("memberName", row.get("member_name"));
    out.put("memberStudentNo", row.get("member_student_no"));
    out.put("technicalVerdict", row.get("technical_verdict"));
    out.put("passedTestCases", row.get("passed_test_cases"));
    out.put("totalTestCases", row.get("total_test_cases"));
    out.put("timeMs", row.get("time_ms"));
    out.put("memoryKb", row.get("memory_kb"));
    out.put("autoScore", row.get("auto_score"));
    out.put("humanScore", row.get("human_score"));
    out.put("finalScore", row.get("final_score"));
    out.put("scoreSource", row.get("score_source"));
    out.put("resultStatus", row.get("result_status"));
    out.put("publishedAt", row.get("published_at") == null ? null : time(row.get("published_at")));
    return out;
  }

  private static Object[] append(List<Object> args, Object... extra) {
    Object[] result = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) result[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) result[args.size() + i] = extra[i];
    return result;
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

  private static ApiException notFound() {
    return new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private static ApiException forbidden() {
    return new ApiException(403, ErrorCode.FORBIDDEN);
  }
}
