package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.SubmissionTutorAi;
import io.github.dream07code.innovationspace.scoring.SubmissionTutorRubric;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;
import io.github.dream07code.innovationspace.service.SubmissionTutorService;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 助教对话实现。
 *
 * <p>上下文只由服务端组装：题目取自题库、代码取自提交记录、判题结果取自 Judge0 落库结果，
 * 前端只能传「问题」和「历史对话」，不能影响模型看到的题目与判定，避免改判题结果骗过模型。
 *
 * <p>不写任何表、不落会话：对话历史由前端逐轮带回，进程内不保存，因此不需要数据库结构变更，
 * 也不存在"会话残留"的清理问题。
 */
@Service
public class SubmissionTutorServiceImpl implements SubmissionTutorService {
  private static final Logger log = LoggerFactory.getLogger(SubmissionTutorServiceImpl.class);
  private static final Set<String> JUDGED_STATUSES = Set.of("JUDGED", "FAILED", "NEEDS_REVIEW");
  private static final int MAX_FAILED_CASES = 3;
  private static final int MAX_HISTORY_TURNS = 12;
  private static final int MAX_TURN_CHARS = 2000;
  private static final int MAX_MESSAGE_CHARS = 1000;
  private static final long AI_WAIT_SECONDS = 20;
  private static final DateTimeFormatter OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
  private static final String NOT_ENABLED =
      "AI 助教未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。";

  private final JdbcTemplate jdbc;
  private final AssessmentSubmissionService submissions;
  private final AssessmentProblemService problems;
  private final SubmissionTutorAi ai;
  private final ScoringProperties aiProperties;
  private final Clock clock;
  private final Semaphore aiSlots;

  public SubmissionTutorServiceImpl(
      JdbcTemplate jdbc,
      AssessmentSubmissionService submissions,
      AssessmentProblemService problems,
      SubmissionTutorAi ai,
      ScoringProperties aiProperties,
      Clock clock) {
    this.jdbc = jdbc;
    this.submissions = submissions;
    this.problems = problems;
    this.ai = ai;
    this.aiProperties = aiProperties;
    this.clock = clock;
    this.aiSlots = new Semaphore(Math.max(2, aiProperties.getMaxConcurrency()));
  }

  @Override
  public Map<String, Object> chat(
      long submissionId, Map<String, Object> request, SessionPrincipal actor) {
    String message = text(request == null ? null : request.get("message"));
    if (message.isBlank())
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "message is required");

    // get() 已做可见性校验：本人、所辖方向负责人、学院管理员。
    var submission = submissions.get(submissionId, actor);
    Object status = submission.get("status");
    if (status == null || !JUDGED_STATUSES.contains(String.valueOf(status)))
      throw new ApiException(409, ErrorCode.INVALID_STATE, "submission is not judged yet");
    @SuppressWarnings("unchecked")
    var judgeResult = (Map<String, Object>) submission.get("judgeResult");
    if (judgeResult == null)
      throw new ApiException(409, ErrorCode.INVALID_STATE, "judge result missing");

    long problemId = Long.parseLong(String.valueOf(submission.get("problemId")));
    var problem = problems.get(problemId, actor);

    var input =
        new SubmissionTutorAi.TutorInput(
            new SubmissionTutorAi.Problem(
                string(problem.get("title")),
                string(problem.get("difficulty")),
                string(problem.get("description")),
                string(problem.get("inputFormat")),
                string(problem.get("outputFormat")),
                string(problem.get("tags"))),
            string(submission.get("language")),
            code(submissionId),
            new SubmissionTutorAi.Judge(
                string(judgeResult.get("verdict")),
                number(judgeResult.get("passedTestCases")),
                number(judgeResult.get("totalTestCases")),
                judgeResult.get("safeError") == null
                    ? null
                    : String.valueOf(judgeResult.get("safeError")),
                failedCases(submissionId)),
            history(request.get("history")),
            cap(message, MAX_MESSAGE_CHARS));

    var out = new LinkedHashMap<String, Object>();
    out.put("promptVersion", SubmissionTutorRubric.VERSION);
    out.put("generatedAt", now());

    if (!aiProperties.isEnabled() || !"openai-compatible".equals(aiProperties.getProvider())) {
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", NOT_ENABLED);
      return out;
    }

    boolean acquired;
    try {
      acquired = aiSlots.tryAcquire(AI_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", "AI 助教繁忙，请稍后重试。");
      return out;
    }
    try {
      var reply = ai.chat(input);
      out.put("available", true);
      out.put("reply", reply.content());
      out.put("model", reply.model());
      return out;
    } catch (SubmissionTutorAi.Unavailable error) {
      log.warn("AI tutor unavailable for submission {}: {}", submissionId, error.getMessage());
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", "AI 助教暂不可用，请稍后重试。");
      return out;
    } finally {
      aiSlots.release();
    }
  }

  /** 历史对话只接受 user/assistant 两种角色，非法项直接丢弃，不让脏结构进提示词。 */
  private List<SubmissionTutorAi.Turn> history(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
    var turns = new ArrayList<SubmissionTutorAi.Turn>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) continue;
      String role = text(map.get("role")).toLowerCase(Locale.ROOT);
      if (!"user".equals(role) && !"assistant".equals(role)) continue;
      String content = text(map.get("content"));
      if (content.isBlank()) continue;
      turns.add(new SubmissionTutorAi.Turn(role, cap(content, MAX_TURN_CHARS)));
    }
    if (turns.size() <= MAX_HISTORY_TURNS) return List.copyOf(turns);
    return List.copyOf(turns.subList(turns.size() - MAX_HISTORY_TURNS, turns.size()));
  }

  private List<SubmissionTutorAi.FailedCase> failedCases(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT position,verdict,input_preview,actual_output,expected_output"
                + " FROM oj_test_case_result WHERE submission_id=? AND verdict<>'ACCEPTED'"
                + " ORDER BY position LIMIT ?",
            submissionId,
            MAX_FAILED_CASES);
    var cases = new ArrayList<SubmissionTutorAi.FailedCase>(rows.size());
    for (var row : rows)
      cases.add(
          new SubmissionTutorAi.FailedCase(
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

  private String now() {
    return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().format(OFFSET);
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static String cap(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
