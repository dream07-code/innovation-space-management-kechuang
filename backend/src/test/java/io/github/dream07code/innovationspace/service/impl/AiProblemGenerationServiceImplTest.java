package io.github.dream07code.innovationspace.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.problemgen.ProblemDraft;
import io.github.dream07code.innovationspace.problemgen.ProblemGenerator;
import io.github.dream07code.innovationspace.security.RateLimitService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * AI 出题的校验与落库边界。
 *
 * <p>这里钉两件事：参考解法跑不过时绝不落库；第一次失败会带着失败原因重生成一次。
 * 判题器与数据库都用替身，真实编译执行放在端到端验证里跑。
 */
class AiProblemGenerationServiceImplTest {
  private static final SessionPrincipal STUDENT =
      new SessionPrincipal(301L, 41L, "STUDENT", List.of(), false, 1L, 9001L);
  private static final long PROBLEM_ID = 2001L;

  private JdbcTemplate jdbc;
  private ProblemGenerator generator;
  private OjAdapter adapter;
  private AssessmentProblemService problems;
  private RateLimitService rateLimit;
  private AiProblemGenerationServiceImpl service;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    generator = mock(ProblemGenerator.class);
    adapter = mock(OjAdapter.class);
    problems = mock(AssessmentProblemService.class);
    rateLimit = mock(RateLimitService.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(PROBLEM_ID);
    when(problems.get(anyLong(), any())).thenReturn(Map.of("id", String.valueOf(PROBLEM_ID)));

    service =
        new AiProblemGenerationServiceImpl(
            jdbc,
            generator,
            adapter,
            problems,
            rateLimit,
            new io.github.dream07code.innovationspace.problemgen.AiProblemCancellationRegistry(),
            Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC),
            manager);
  }

  @Test
  void savesProblemWhenReferenceSolutionPassesAllCases() {
    when(generator.generate(any())).thenReturn(draft());
    when(adapter.judge(any())).thenReturn(accepted());

    var result = service.generate(request(), STUDENT);

    @SuppressWarnings("unchecked")
    var problem = (Map<String, Object>) result.get("problem");
    assertEquals(String.valueOf(PROBLEM_ID), problem.get("id"));
    // 参考解法与测试点随生成结果返回，学生能看到「这题该怎么做」。
    assertTrue(((String) result.get("referenceCode")).contains("class Solution"));
    assertEquals(4, ((List<?>) result.get("testCases")).size());
    verify(generator).generate(any());
    // 4 个测试点逐个真跑：参考解法必须全部通过。
    verify(adapter, times(4)).judge(any());
    verify(problems).get(PROBLEM_ID, STUDENT);
  }

  @Test
  void retriesOnceWithFeedbackThenSaves() {
    when(generator.generate(any())).thenReturn(draft());
    // 第一次第一个用例就失败，第二次全部通过。
    when(adapter.judge(any()))
        .thenReturn(wrongAnswer())
        .thenReturn(accepted(), accepted(), accepted(), accepted());

    var result = service.generate(request(), STUDENT);

    assertNotNull(result.get("problem"));
    var requests = ArgumentCaptor.forClass(ProblemGenerator.Request.class);
    verify(generator, times(2)).generate(requests.capture());
    List<ProblemGenerator.Request> captured = new ArrayList<>(requests.getAllValues());
    // 第一次没有反馈，第二次带上失败原因，模型才有机会纠正。
    assertEquals(null, captured.get(0).feedback());
    assertTrue(captured.get(1).feedback().contains("第 1 个测试点"), captured.get(1).feedback());
  }

  @Test
  void failsWithoutWritingWhenBothAttemptsFail() {
    when(generator.generate(any())).thenReturn(draft());
    when(adapter.judge(any())).thenReturn(wrongAnswer());

    var error = assertThrows(ApiException.class, () -> service.generate(request(), STUDENT));

    assertEquals(503, error.status());
    // 两次都不过就结束，不写任何题目数据。
    verify(generator, times(2)).generate(any());
    verify(jdbc, never()).queryForObject(anyString(), eq(Long.class));
    verify(problems, never()).get(anyLong(), any());
  }

  @Test
  void rejectsNonStudent() {
    var teacher = new SessionPrincipal(302L, 42L, "DIRECTION_LEADER", List.of(1L), false, 1L, 9002L);

    var error = assertThrows(ApiException.class, () -> service.generate(request(), teacher));

    assertEquals(403, error.status());
    verify(generator, never()).generate(any());
  }

  @Test
  void rejectsUnknownField() {
    var request = new java.util.LinkedHashMap<>(request());
    request.put("count", 3);

    var error = assertThrows(ApiException.class, () -> service.generate(request, STUDENT));

    assertEquals(400, error.status());
  }

  @Test
  void rejectsUnsupportedDifficulty() {
    var request = new java.util.LinkedHashMap<>(request());
    request.put("difficulty", "INSANE");

    var error = assertThrows(ApiException.class, () -> service.generate(request, STUDENT));

    assertEquals(400, error.status());
  }

  private static Map<String, Object> request() {
    return Map.of("topic", "动态规划", "difficulty", "MEDIUM", "category", "数组");
  }

  private static OjJudgeResult accepted() {
    return new OjJudgeResult(OjVerdict.ACCEPTED, 1, 1, 12, 2048, null);
  }

  private static OjJudgeResult wrongAnswer() {
    return new OjJudgeResult(
        OjVerdict.WRONG_ANSWER,
        0,
        1,
        12,
        2048,
        null,
        List.of(
            new OjJudgeResult.TestCaseResult(
                1, OjVerdict.WRONG_ANSWER, 12, 2048, null, "0")));
  }

  private static ProblemDraft draft() {
    return new ProblemDraft(
        "最大子数组和",
        "求连续子数组的最大和",
        "给定整数数组 nums，返回和最大的连续子数组的和。",
        "参数 nums：整数数组。",
        "返回一个整数。",
        List.of(
            Map.of("input", "[[-2,1,-3,4,-1,2,1,-5,4]]", "output", "6"),
            Map.of("input", "[[1]]", "output", "1")),
        List.of("动态规划", "数组"),
        "maxSubArray",
        List.of("int[]"),
        "int",
        2000,
        262144,
        "class Solution { public int maxSubArray(int[] nums) { return 0; } }",
        List.of(
            new ProblemDraft.TestCase("[[-2,1,-3,4,-1,2,1,-5,4]]", "6", false),
            new ProblemDraft.TestCase("[[1]]", "1", false),
            new ProblemDraft.TestCase("[[5,4,-1,7,8]]", "23", false),
            new ProblemDraft.TestCase("[[-1]]", "-1", true)));
  }
}
