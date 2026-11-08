package io.github.dream07code.innovationspace.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.SubmissionReportAi;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.AssessmentSubmissionService;
import io.github.dream07code.innovationspace.service.QuizReviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 批改报告的缓存语义。
 *
 * <p>只缓存成功的 AI 结论：AI 没配好时返回的「不可用」一旦被缓存，用户配好密钥后
 * 还要等整个 TTL 才能看到真实批改，这是必须钉死的行为。
 */
class SubmissionReportServiceImplTest {
  private static final long SUBMISSION_ID = 7001L;
  private static final long PROBLEM_ID = 6001L;
  private static final long ASSESSMENT_ID = 5001L;
  private static final SessionPrincipal ACTOR =
      new SessionPrincipal(1L, 101L, "STUDENT", List.of(), false, 1L, 1L);

  private SubmissionReportAi ai;
  private SubmissionReportServiceImpl service;

  @BeforeEach
  void setUp() {
    var submissions = mock(AssessmentSubmissionService.class);
    var problems = mock(AssessmentProblemService.class);
    var quizReviews = mock(QuizReviewService.class);
    ai = mock(SubmissionReportAi.class);

    when(submissions.get(anyLong(), any())).thenReturn(submission());
    when(problems.get(anyLong(), any())).thenReturn(problem());
    when(quizReviews.summary(anyLong(), any())).thenReturn(Map.of("questions", List.of()));

    var properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("openai-compatible");
    properties.setBaseUrl("https://example.invalid");
    properties.setApiKey("k");
    properties.setModel("mimo-v2.6-flash");

    service =
        new SubmissionReportServiceImpl(
            fakeJdbc(),
            submissions,
            problems,
            quizReviews,
            ai,
            properties,
            Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void doesNotCacheFailureSoRetryHappensAfterKeyIsFixed() {
    when(ai.analyze(any()))
        .thenThrow(new SubmissionReportAi.Unavailable("AI report provider is not configured"))
        .thenReturn(new SubmissionReportAi.Analysis("第 2 行应为 a+b", List.of("改成加法"), "mimo"));

    var first = service.report(SUBMISSION_ID, ACTOR);
    var second = service.report(SUBMISSION_ID, ACTOR);

    assertEquals(false, ai(first).get("available"));
    assertNotNull(ai(first).get("error"));
    // 第二次必须真的再调一次模型，而不是拿上一次的失败结果顶着
    verify(ai, times(2)).analyze(any());
    assertEquals(true, ai(second).get("available"));
    assertEquals("第 2 行应为 a+b", ai(second).get("cause"));
    assertEquals(false, ai(second).get("cached"));
  }

  @Test
  void cachesSuccessfulResultToAvoidPayingTwice() {
    when(ai.analyze(any()))
        .thenReturn(new SubmissionReportAi.Analysis("边界少一位", List.of("把 < 改成 <="), "mimo"));

    var first = service.report(SUBMISSION_ID, ACTOR);
    var second = service.report(SUBMISSION_ID, ACTOR);

    verify(ai, times(1)).analyze(any());
    assertEquals(false, ai(first).get("cached"));
    assertEquals(true, ai(second).get("cached"));
    assertEquals("边界少一位", ai(second).get("cause"));
  }

  @Test
  void reportsRecommendationEvenWhenAiIsDown() {
    when(ai.analyze(any())).thenThrow(new SubmissionReportAi.Unavailable("boom"));

    var body = service.report(SUBMISSION_ID, ACTOR);

    @SuppressWarnings("unchecked")
    var recommendation = (Map<String, Object>) body.get("recommendation");
    assertEquals("SAME_DIFFICULTY", recommendation.get("rule"));
    assertNotNull(recommendation.get("problem"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> ai(Map<String, Object> body) {
    return (Map<String, Object>) body.get("ai");
  }

  private static Map<String, Object> submission() {
    var judge = new LinkedHashMap<String, Object>();
    judge.put("verdict", "WRONG_ANSWER");
    judge.put("passedTestCases", 3);
    judge.put("totalTestCases", 5);
    judge.put("timeMs", 120);
    judge.put("memoryKb", 32768);
    judge.put("safeError", null);
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(SUBMISSION_ID));
    out.put("assessmentId", String.valueOf(ASSESSMENT_ID));
    out.put("problemId", String.valueOf(PROBLEM_ID));
    out.put("language", "python");
    out.put("status", "JUDGED");
    out.put("memberName", "测试学生");
    out.put("submittedAt", "2026-11-08T20:10:00+08:00");
    out.put("judgedAt", "2026-11-08T20:10:02+08:00");
    out.put("judgeResult", judge);
    return out;
  }

  private static Map<String, Object> problem() {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(PROBLEM_ID));
    out.put("title", "两数之和");
    out.put("difficulty", "EASY");
    out.put("summary", "求和");
    out.put("description", "读入两个整数，输出它们的和。");
    out.put("tags", List.of("基础"));
    out.put("timeLimitMs", 2000);
    return out;
  }

  /** 只回答报告服务真正会发的几条查询，避免为了一个单测启动数据库。 */
  private static JdbcTemplate fakeJdbc() {
    return new JdbcTemplate() {
      @Override
      public List<Map<String, Object>> queryForList(String sql, Object... args) {
        String normalized = sql.replaceAll("\\s+", " ");
        var rows = new ArrayList<Map<String, Object>>();
        if (normalized.contains("assessment_submission_code")) {
          rows.add(Map.of("code_text", "a,b=map(int,input().split())\nprint(a-b)"));
        } else if (normalized.contains("oj_test_case_result")) {
          var row = new LinkedHashMap<String, Object>();
          row.put("position", 4);
          row.put("verdict", "WRONG_ANSWER");
          row.put("input_preview", "2 3");
          row.put("actual_output", "-1");
          row.put("expected_output", "5");
          rows.add(row);
        } else if (normalized.contains("FROM assessment WHERE")) {
          rows.add(Map.of("title", "第一次阶段考核"));
        } else if (normalized.contains("assessment_item")) {
          rows.add(Map.of("id", 6002L, "title", "三数最大值", "difficulty", "EASY"));
        } else if (normalized.contains("FROM assessment_problem WHERE status")) {
          rows.add(Map.of("id", 6003L, "title", "全库同难度题", "difficulty", "EASY"));
        }
        return rows;
      }

      @Override
      public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return null;
      }
    };
  }
}
