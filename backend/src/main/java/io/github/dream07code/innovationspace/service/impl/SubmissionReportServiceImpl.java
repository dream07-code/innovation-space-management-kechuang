package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.assessment.NextProblemPolicy;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.SubmissionReportAi;
import io.github.dream07code.innovationspace.scoring.SubmissionReportRubric;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;
import io.github.dream07code.innovationspace.service.QuizReviewService;
import io.github.dream07code.innovationspace.service.SubmissionReportService;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 批改报告组装。
 *
 * <p>判定、题干、失败用例全部来自既有表，AI 结论来自既有的 openai-compatible 配置；
 * 本服务不写任何表——不新增数据库结构，也不把模型结论落库当成绩。
 *
 * <p>同一份提交的 AI 结论按提交 id 缓存在进程内（提交判完即不可变），
 * 避免刷新页面重复计费；缓存有界且带 TTL，模型结论改动后最多等一个 TTL。
 */
@Service
public class SubmissionReportServiceImpl implements SubmissionReportService {
  private static final Logger log = LoggerFactory.getLogger(SubmissionReportServiceImpl.class);
  private static final Set<String> JUDGED_STATUSES = Set.of("JUDGED", "FAILED", "NEEDS_REVIEW");
  private static final int MAX_FAILED_CASES = 3;
  private static final int MAX_CACHE_ENTRIES = 200;
  private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
  private static final long AI_WAIT_SECONDS = 20;
  private static final DateTimeFormatter OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  private final JdbcTemplate jdbc;
  private final AssessmentSubmissionService submissions;
  private final AssessmentProblemService problems;
  private final QuizReviewService quizReviews;
  private final SubmissionReportAi ai;
  private final ScoringProperties aiProperties;
  private final Clock clock;

  private final Map<Long, Cached> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Cached> eldest) {
              return size() > MAX_CACHE_ENTRIES;
            }
          });
  private final Semaphore aiSlots;

  public SubmissionReportServiceImpl(
      JdbcTemplate jdbc,
      AssessmentSubmissionService submissions,
      AssessmentProblemService problems,
      QuizReviewService quizReviews,
      SubmissionReportAi ai,
      ScoringProperties aiProperties,
      Clock clock) {
    this.jdbc = jdbc;
    this.submissions = submissions;
    this.problems = problems;
    this.quizReviews = quizReviews;
    this.ai = ai;
    this.aiProperties = aiProperties;
    this.clock = clock;
    this.aiSlots = new Semaphore(Math.max(2, aiProperties.getMaxConcurrency()));
  }

  @Override
  public Map<String, Object> report(long submissionId, SessionPrincipal actor) {
    // get() 已经做了可见性校验（本人 / 所辖方向 / 学院管理员），这里不重复实现权限。
    var submission = submissions.get(submissionId, actor);
    Object status = submission.get("status");
    if (status == null || !JUDGED_STATUSES.contains(String.valueOf(status)))
      throw new ApiException(409, ErrorCode.INVALID_STATE, "submission is not judged yet");
    @SuppressWarnings("unchecked")
    var judge = (Map<String, Object>) submission.get("judgeResult");
    if (judge == null) throw new ApiException(409, ErrorCode.INVALID_STATE, "judge result missing");

    long problemId = Long.parseLong(String.valueOf(submission.get("problemId")));
    long assessmentId = Long.parseLong(String.valueOf(submission.get("assessmentId")));
    var problem = problems.get(problemId, actor);

    String verdict = String.valueOf(judge.get("verdict"));
    boolean accepted = "ACCEPTED".equals(verdict);
    int passed = number(judge.get("passedTestCases"));
    int total = number(judge.get("totalTestCases"));

    var input =
        new SubmissionReportAi.ReportInput(
            string(problem.get("title")),
            string(problem.get("difficulty")),
            string(problem.get("description")),
            string(submission.get("language")),
            code(submissionId),
            verdict,
            passed,
            total,
            judge.get("safeError") == null ? null : String.valueOf(judge.get("safeError")),
            failedCases(submissionId));

    var target = NextProblemPolicy.target(string(problem.get("difficulty")), accepted);
    var recommendation = recommendation(assessmentId, problemId, target);

    var analysis = analysis(submissionId, input);

    var out = new LinkedHashMap<String, Object>();
    var submissionOut = new LinkedHashMap<String, Object>();
    submissionOut.put("id", String.valueOf(submissionId));
    submissionOut.put("status", status);
    submissionOut.put("language", submission.get("language"));
    submissionOut.put("memberName", submission.get("memberName"));
    submissionOut.put("submittedAt", submission.get("submittedAt"));
    submissionOut.put("judgedAt", submission.get("judgedAt"));
    submissionOut.put("assessmentTitle", assessmentTitle(assessmentId));
    out.put("submission", submissionOut);

    var problemOut = new LinkedHashMap<String, Object>();
    problemOut.put("id", String.valueOf(problemId));
    problemOut.put("title", problem.get("title"));
    problemOut.put("difficulty", problem.get("difficulty"));
    problemOut.put("summary", problem.get("summary"));
    problemOut.put("description", problem.get("description"));
    problemOut.put("inputFormat", problem.get("inputFormat"));
    problemOut.put("outputFormat", problem.get("outputFormat"));
    problemOut.put("tags", problem.get("tags"));
    problemOut.put("timeLimitMs", problem.get("timeLimitMs"));
    out.put("problem", problemOut);

    var judgeOut = new LinkedHashMap<String, Object>();
    judgeOut.put("verdict", verdict);
    judgeOut.put("accepted", accepted);
    judgeOut.put("passedTestCases", passed);
    judgeOut.put("totalTestCases", total);
    judgeOut.put("timeMs", judge.get("timeMs"));
    judgeOut.put("memoryKb", judge.get("memoryKb"));
    judgeOut.put("safeError", judge.get("safeError"));
    judgeOut.put("failedCases", input.failedCases());
    out.put("judge", judgeOut);

    var aiOut = new LinkedHashMap<String, Object>();
    aiOut.put("available", analysis.analysis() != null);
    aiOut.put("model", analysis.analysis() == null ? null : analysis.analysis().model());
    aiOut.put("rubricVersion", SubmissionReportRubric.VERSION);
    aiOut.put("cause", analysis.analysis() == null ? null : analysis.analysis().cause());
    aiOut.put("advice", analysis.analysis() == null ? List.of() : analysis.analysis().advice());
    aiOut.put("cached", analysis.cached());
    aiOut.put("generatedAt", analysis.generatedAt());
    if (analysis.error() != null) aiOut.put("error", analysis.error());
    out.put("ai", aiOut);

    out.put("recommendation", recommendation);

    // 客观题题干来自阅卷汇总接口的 questions 字段，仅管理角色可见（该接口本身要求管理权限）。
    out.put("paperQuestions", paperQuestions(assessmentId, actor));
    return out;
  }

  private record AnalysisResult(
      SubmissionReportAi.Analysis analysis, boolean cached, String generatedAt, String error) {}

  /** 命中缓存直接用；未配置或调用失败时返回 null 结论 + 一句可展示的原因，不编造批改内容。 */
  private AnalysisResult analysis(long submissionId, SubmissionReportAi.ReportInput input) {
    var hit = cache.get(submissionId);
    if (hit != null && clock.millis() - hit.createdAt() < CACHE_TTL_MS)
      return new AnalysisResult(hit.analysis(), true, hit.generatedAt(), null);

    if (!aiProperties.isEnabled() || !"openai-compatible".equals(aiProperties.getProvider()))
      return new AnalysisResult(
          null, false, null, "AI 批改未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。");

    boolean acquired;
    try {
      acquired = aiSlots.tryAcquire(AI_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired)
      return new AnalysisResult(null, false, null, "AI 批改繁忙，请稍后重试。");
    try {
      var analysis = ai.analyze(input);
      String generatedAt = now();
      cache.put(submissionId, new Cached(analysis, clock.millis(), generatedAt));
      return new AnalysisResult(analysis, false, generatedAt, null);
    } catch (SubmissionReportAi.Unavailable error) {
      log.warn("AI report unavailable for submission {}: {}", submissionId, error.getMessage());
      return new AnalysisResult(null, false, null, "AI 批改暂不可用，请稍后重试。");
    } finally {
      aiSlots.release();
    }
  }

  private Map<String, Object> recommendation(
      long assessmentId, long problemId, NextProblemPolicy.Target target) {
    var out = new LinkedHashMap<String, Object>();
    out.put("rule", target.rule());
    out.put("targetDifficulty", target.difficulty());
    var next =
        firstProblem(
            "SELECT p.id,p.title,p.difficulty FROM assessment_problem p"
                + " JOIN assessment_item i ON i.problem_id=p.id"
                + " WHERE i.assessment_id=? AND i.item_type='PROGRAM' AND p.status='PUBLISHED'"
                + " AND p.difficulty=? AND p.id<>? ORDER BY i.position LIMIT 1",
            assessmentId,
            target.difficulty(),
            problemId);
    if (next == null)
      next =
          firstProblem(
              "SELECT id,title,difficulty FROM assessment_problem"
                  + " WHERE status='PUBLISHED' AND difficulty=? AND id<>? ORDER BY id LIMIT 1",
              target.difficulty(),
              problemId);
    out.put("reason", next == null ? target.reason() + "当前题库没有匹配难度的其他题目。" : target.reason());
    out.put("problem", next);
    return out;
  }

  private Map<String, Object> firstProblem(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("title", row.get("title"));
    out.put("difficulty", row.get("difficulty"));
    return out;
  }

  private List<SubmissionReportAi.FailedCase> failedCases(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT position,verdict,input_preview,actual_output,expected_output"
                + " FROM oj_test_case_result WHERE submission_id=? AND verdict<>'ACCEPTED'"
                + " ORDER BY position LIMIT ?",
            submissionId,
            MAX_FAILED_CASES);
    var cases = new ArrayList<SubmissionReportAi.FailedCase>(rows.size());
    for (var row : rows)
      cases.add(
          new SubmissionReportAi.FailedCase(
              number(row.get("position")),
              string(row.get("verdict")),
              string(row.get("input_preview")),
              row.get("actual_output") == null ? null : String.valueOf(row.get("actual_output")),
              string(row.get("expected_output"))));
    return cases;
  }

  private String code(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT code_text FROM assessment_submission_code WHERE submission_id=?", submissionId);
    return rows.isEmpty() ? "" : string(rows.getFirst().get("code_text"));
  }

  private String assessmentTitle(long assessmentId) {
    var rows = jdbc.queryForList("SELECT title FROM assessment WHERE id=?", assessmentId);
    return rows.isEmpty() ? null : string(rows.getFirst().get("title"));
  }

  /** 阅卷汇总的 questions 里带标准答案与解析，这里只取题号与题干，避免批改报告顺带泄露答案。 */
  private List<Object> paperQuestions(long assessmentId, SessionPrincipal actor) {
    if ("STUDENT".equals(actor.role())) return List.of();
    try {
      var summary = quizReviews.summary(assessmentId, actor);
      Object questions = summary == null ? null : summary.get("questions");
      if (!(questions instanceof List<?> list)) return List.of();
      var out = new ArrayList<>(list.size());
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> question)) continue;
        var trimmed = new LinkedHashMap<String, Object>();
        trimmed.put("position", question.get("position"));
        trimmed.put("stem", question.get("stem"));
        out.add(trimmed);
      }
      return out;
    } catch (RuntimeException error) {
      // 客观题清单是增强信息，取不到不影响批改报告本身。
      log.debug("quiz questions unavailable for assessment {}: {}", assessmentId, error.getMessage());
      return List.of();
    }
  }

  private String now() {
    return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().format(OFFSET);
  }

  private record Cached(SubmissionReportAi.Analysis analysis, long createdAt, String generatedAt) {}

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }
}
