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
    Boolean published =
        jdbc.queryForObject(
            "SELECT results_published_at IS NOT NULL FROM assessment WHERE id=?",
            Boolean.class,
            assessmentId);
    boolean hideScores = "STUDENT".equals(actor.role()) && !Boolean.TRUE.equals(published);

    // 条目口径：题数与满分直接来自混合条目表（程序题+客观题）。
    var itemAgg =
        jdbc.queryForMap(
            "SELECT COUNT(*) AS item_count, COALESCE(SUM(score),0) AS max_score"
                + " FROM assessment_item WHERE assessment_id=?",
            assessmentId);
    int itemCount = ((Number) itemAgg.get("item_count")).intValue();
    int maxScore = ((Number) itemAgg.get("max_score")).intValue();

    // 已判分客观题明细数（按成员），供 completedCount 用。
    var detailRows =
        jdbc.queryForList(
            "SELECT s.member_id AS member_id, COUNT(d.position) AS graded_quiz"
                + " FROM assessment_answer_sheet s"
                + " LEFT JOIN assessment_answer_sheet_item d ON d.sheet_id=s.id"
                + " WHERE s.assessment_id=? AND s.status='SUBMITTED' AND s.graded_at IS NOT NULL"
                + " GROUP BY s.member_id",
            assessmentId);
    var gradedQuizByMember = new java.util.HashMap<Long, Integer>();
    for (var row : detailRows)
      gradedQuizByMember.put(
          ((Number) row.get("member_id")).longValue(),
          ((Number) row.get("graded_quiz")).intValue());

    var filterArgs = new ArrayList<Object>();
    filterArgs.add(directionId);
    var filter = new StringBuilder(" WHERE m.direction_id=? AND m.membership_status='ACTIVE'");
    if ("STUDENT".equals(actor.role())) {
      filter.append(" AND m.id=?");
      filterArgs.add(actor.memberId());
    }

    // 程序题取每人每题最后一次提交；客观题得分取答题卡 total_score。
    String sql =
        "WITH latest AS ("
            + " SELECT s.id AS submission_id,s.member_id,s.problem_id,"
            + " ROW_NUMBER() OVER (PARTITION BY s.member_id,s.problem_id"
            + " ORDER BY s.submitted_at DESC,s.id DESC) AS rn"
            + " FROM assessment_submission s WHERE s.assessment_id=?"
            + ") SELECT m.id AS member_id,m.name AS member_name,m.student_no AS student_no,"
            + " COUNT(l.submission_id) AS answered,"
            + " COALESCE(SUM(CASE WHEN r.result_status='COMPLETE' THEN 1 ELSE 0 END),0) AS completed,"
            + " COALESCE(SUM(r.final_score),0) AS program_score,"
            + " sh.answers AS sheet_answers, sh.total_score AS sheet_score, sh.status AS sheet_status,"
            + " sh.submitted_at AS sheet_submitted_at,"
            + " MAX(s.submitted_at) AS last_program_at"
            + " FROM member m"
            + " LEFT JOIN latest l ON l.member_id=m.id AND l.rn=1"
            + " LEFT JOIN assessment_submission s ON s.id=l.submission_id"
            + " LEFT JOIN assessment_result r ON r.submission_id=l.submission_id"
            + " LEFT JOIN assessment_answer_sheet sh ON sh.assessment_id=? AND sh.member_id=m.id"
            + filter
            + " GROUP BY m.id,m.name,m.student_no,sh.answers,sh.total_score,sh.status,sh.submitted_at"
            + " ORDER BY (COALESCE(SUM(r.final_score),0)+COALESCE(sh.total_score,0)) DESC,student_no ASC,m.id ASC"
            + " LIMIT ? OFFSET ?";
    var args = new ArrayList<Object>();
    args.add(assessmentId);
    args.add(assessmentId);
    args.addAll(filterArgs);
    args.add(size);
    args.add((page - 1) * size);

    var rows = jdbc.queryForList(sql, args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      int answeredPrograms = number(row.get("answered"));
      int completedPrograms = number(row.get("completed"));
      int programScore = number(row.get("program_score"));
      Integer sheetScore =
          row.get("sheet_score") == null ? null : ((Number) row.get("sheet_score")).intValue();
      int sheetAnswered = countAnswers(row.get("sheet_answers"));
      int gradedQuiz = gradedQuizByMember.getOrDefault(((Number) row.get("member_id")).longValue(), 0);
      int answered = answeredPrograms + sheetAnswered;
      int completed = completedPrograms + gradedQuiz;
      Integer totalScore = hideScores ? null : programScore + (sheetScore == null ? 0 : sheetScore);
      var out = new LinkedHashMap<String, Object>();
      out.put("memberId", String.valueOf(row.get("member_id")));
      out.put("memberName", row.get("member_name"));
      out.put("memberStudentNo", row.get("student_no"));
      out.put("itemCount", itemCount);
      out.put("answeredCount", answered);
      out.put("completedCount", completed);
      out.put("pendingCount", Math.max(0, answered - completed));
      out.put("totalScore", totalScore);
      out.put("maxScore", maxScore);
      // 客观题按「交卷统一判」设计：草稿/未作答时 sheetScore 为空，结果页据
      // sheetStatus 显示「未交卷」而不是把没有的分数误报成待人工评分。
      out.put("sheetStatus", row.get("sheet_status") == null ? "NONE" : String.valueOf(row.get("sheet_status")));
      out.put("sheetScore", hideScores ? null : sheetScore);
      // 得分率 = round(total×100/满分)；分值制下"每题平均分"已无直观意义。
      out.put(
          "scoreRate",
          hideScores || maxScore <= 0 ? null : (int) Math.round(totalScore * 100.0 / maxScore));
      java.time.Instant sheetAt = toInstant(row.get("sheet_submitted_at"));
      java.time.Instant programAt = toInstant(row.get("last_program_at"));
      java.time.Instant lastAt =
          sheetAt != null && (programAt == null || sheetAt.isAfter(programAt)) ? sheetAt : programAt;
      out.put("lastSubmittedAt", lastAt == null ? null : time(lastAt));
      items.add(out);
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM member m" + filter, Long.class, filterArgs.toArray());
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("page", page);
    result.put("size", size);
    result.put("total", total == null ? 0L : total);
    result.put("resultsPublished", Boolean.TRUE.equals(published));
    return result;
  }

  /** JDBC 对 DATETIME 可能给 Timestamp 或 LocalDateTime，统一转 Instant。 */
  private static java.time.Instant toInstant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
    if (value instanceof java.time.Instant instant) return instant;
    return null;
  }

  /** 答题卡 answers JSON 里非空答案的条数（未答不算）。 */
  private static int countAnswers(Object answersJson) {
    if (answersJson == null) return 0;
    try {
      var raw = tools.jackson.databind.json.JsonMapper.builder()
          .build().readValue(String.valueOf(answersJson), Map.class);
      int count = 0;
      for (var value : raw.values()) {
        if (value == null) continue;
        if (value instanceof String text && text.isBlank()) continue;
        count++;
      }
      return count;
    } catch (RuntimeException error) {
      return 0;
    }
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
