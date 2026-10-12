package io.github.dream07code.innovationspace.assessment.service;

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
public class AssessmentResultService {
  private final JdbcTemplate jdbc;
  private final AssessmentEvaluationService evaluations;

  public AssessmentResultService(JdbcTemplate jdbc, AssessmentEvaluationService evaluations) {
    this.jdbc = jdbc;
    this.evaluations = evaluations;
  }

  public Map<String, Object> list(long assessmentId, SessionPrincipal actor, int page, int size) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    var assessments = jdbc.queryForList("SELECT direction_id FROM assessment WHERE id=?", assessmentId);
    if (assessments.isEmpty()) throw notFound();
    long directionId = ((Number) assessments.getFirst().get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) {
      // all results
    } else if ("DIRECTION_LEADER".equals(actor.role())) {
      if (!actor.managedDirectionIds().contains(directionId)) throw notFound();
    } else if ("STUDENT".equals(actor.role()) && actor.memberId() != null) {
      // student scope is applied below
    } else throw forbidden();

    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE s.assessment_id=?");
    args.add(assessmentId);
    if ("STUDENT".equals(actor.role())) {
      where.append(" AND s.member_id=?");
      args.add(actor.memberId());
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

  private Map<String, Object> result(long submissionId) {
    evaluations.refreshResult(submissionId);
    var rows =
        jdbc.queryForList(
            "SELECT r.*,s.assessment_id,s.problem_id,s.member_id FROM assessment_result r"
                + " JOIN assessment_submission s ON s.id=r.submission_id WHERE r.submission_id=?",
            submissionId);
    if (rows.isEmpty()) throw notFound();
    var row = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put("assessmentId", String.valueOf(row.get("assessment_id")));
    out.put("submissionId", String.valueOf(row.get("submission_id")));
    out.put("problemId", String.valueOf(row.get("problem_id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("technicalVerdict", row.get("technical_verdict"));
    out.put("passedTestCases", row.get("passed_test_cases"));
    out.put("totalTestCases", row.get("total_test_cases"));
    out.put("timeMs", row.get("time_ms"));
    out.put("memoryKb", row.get("memory_kb"));
    out.put("aiScore", row.get("ai_score"));
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
