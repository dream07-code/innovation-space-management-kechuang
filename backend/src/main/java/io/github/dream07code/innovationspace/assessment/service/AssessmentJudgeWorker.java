package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.FunctionHarness;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssessmentJudgeWorker {
  private static final Logger log = LoggerFactory.getLogger(AssessmentJudgeWorker.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;
  private final OjAdapter adapter;
  private final Clock clock;
  private final AssessmentEvaluationService evaluations;
  private final TransactionTemplate tx;
  private final ThreadPoolExecutor executor;

  public AssessmentJudgeWorker(
      JdbcTemplate jdbc,
      OjAdapter adapter,
      Clock clock,
      AssessmentEvaluationService evaluations,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.adapter = adapter;
    this.clock = clock;
    this.evaluations = evaluations;
    this.tx = new TransactionTemplate(transactions);
    this.executor =
        new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void enqueue(long submissionId) {
    try {
      executor.execute(() -> judge(submissionId));
    } catch (RejectedExecutionException error) {
      markFailed(submissionId);
    }
  }

  private void judge(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT s.language,s.status,s.problem_id,c.code_text,"
                + "p.judge_mode,p.function_name,p.parameter_types,p.return_type"
                + " FROM assessment_submission s"
                + " JOIN assessment_submission_code c ON c.submission_id=s.id"
                + " JOIN assessment_problem p ON p.id=s.problem_id"
                + " WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    if (!"RECEIVED".equals(row.get("status")) && !"JUDGING".equals(row.get("status"))) return;
    jdbc.update(
        "UPDATE assessment_submission SET status='JUDGING',updated_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        submissionId);

    boolean functionMode = "FUNCTION".equals(row.get("judge_mode"));
    List<OjSubmission.TestCase> testCases =
        assessmentTestCases(
            ((Number) row.get("problem_id")).longValue(),
            functionMode ? signature(row) : null);
    OjJudgeResult result;
    try {
      Object languageValue = row.get("language");
      Object codeValue = row.get("code_text");
      String language = (String) languageValue;
      String code =
          codeValue instanceof String text
              ? text
              : new String((byte[]) codeValue, StandardCharsets.UTF_8);
      result =
          functionMode
              ? judgeFunction(signature(row), language, code, testCases)
              : adapter.judge(new OjSubmission(language, code, testCases));
    } catch (RuntimeException error) {
      log.warn(
          "Submission {} judge request failed: {}",
          submissionId,
          error.getClass().getSimpleName());
      result = internalResult(testCases);
    }
    if (result.verdict() == OjVerdict.INTERNAL_ERROR)
      log.warn(
          "Submission {} judged INTERNAL_ERROR across {} test cases; check OJ provider logs",
          submissionId,
          result.totalTestCases());
    persist(submissionId, result, testCases);
    evaluations.evaluateAfterJudge(submissionId);
  }

  /**
   * 函数式判题：每个测试点把参数嵌进入口代码单独提交，再把逐点结果合并成一次判题结果。
   * 这样不需要引入额外的 stdin 解析协议，各语言只要生成字面量即可。
   */
  private OjJudgeResult judgeFunction(
      FunctionHarness.Signature signature,
      String language,
      String code,
      List<OjSubmission.TestCase> testCases) {
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    int passed = 0;
    int maxTimeMs = 0;
    int maxMemoryKb = 0;
    OjVerdict verdict = OjVerdict.ACCEPTED;
    String safeError = null;
    for (int index = 0; index < testCases.size(); index++) {
      var testCase = testCases.get(index);
      String wrapped;
      try {
        JsonNode arguments = FunctionHarness.parseArguments(signature, testCase.input());
        wrapped = FunctionHarness.wrap(language, signature, arguments, code);
      } catch (IllegalArgumentException error) {
        log.warn("Submission test case #{} has invalid arguments: {}", index + 1, error.getMessage());
        return internalResult(testCases);
      }
      var single =
          adapter.judge(
              new OjSubmission(
                  language, wrapped, List.of(new OjSubmission.TestCase("", testCase.expectedOutput()))));
      if (single.verdict() == OjVerdict.INTERNAL_ERROR) return internalResult(testCases);
      var one =
          single.testCaseResults().isEmpty()
              ? new OjJudgeResult.TestCaseResult(1, single.verdict(), single.timeMs(), single.memoryKb(), single.safeError())
              : single.testCaseResults().getFirst();
      results.add(
          new OjJudgeResult.TestCaseResult(
              index + 1,
              one.verdict(),
              one.timeMs(),
              one.memoryKb(),
              one.safeError(),
              one.actualOutput()));
      if (one.verdict() == OjVerdict.ACCEPTED) {
        passed++;
      } else if (verdict == OjVerdict.ACCEPTED) {
        verdict = one.verdict();
        safeError = one.safeError();
      }
      maxTimeMs = Math.max(maxTimeMs, one.timeMs());
      maxMemoryKb = Math.max(maxMemoryKb, one.memoryKb());
    }
    return new OjJudgeResult(
        verdict, passed, testCases.size(), maxTimeMs, maxMemoryKb, safeError, results);
  }

  private static FunctionHarness.Signature signature(java.util.Map<String, Object> row) {
    List<String> parameters = List.of();
    Object raw = row.get("parameter_types");
    if (raw != null) {
      try {
        parameters = JSON.readValue(String.valueOf(raw), List.class);
      } catch (RuntimeException error) {
        parameters = List.of();
      }
    }
    return FunctionHarness.signature(
        String.valueOf(row.get("function_name")),
        parameters,
        String.valueOf(row.get("return_type")));
  }

  private void persist(
      long submissionId, OjJudgeResult result, List<OjSubmission.TestCase> testCases) {
    var now = Timestamp.from(clock.instant());
    boolean internal = result.verdict() == OjVerdict.INTERNAL_ERROR;
    tx.execute(
        status -> {
          persistJudgeSummary(submissionId, result, internal, now);
          persistTestCaseResults(submissionId, result.testCaseResults(), testCases, now);
          return null;
        });
  }

  private void persistJudgeSummary(
      long submissionId, OjJudgeResult result, boolean internal, Timestamp now) {
    jdbc.update(
        "INSERT INTO oj_judge_result(submission_id,verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,safe_error,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)"
            + " ON DUPLICATE KEY UPDATE verdict=VALUES(verdict),passed_test_cases=VALUES(passed_test_cases),"
            + " total_test_cases=VALUES(total_test_cases),time_ms=VALUES(time_ms),memory_kb=VALUES(memory_kb),"
            + " safe_error=VALUES(safe_error),created_at=VALUES(created_at)",
        submissionId,
        result.verdict().name(),
        result.passedTestCases(),
        result.totalTestCases(),
        result.timeMs(),
        result.memoryKb(),
        result.safeError(),
        now);
    jdbc.update(
        "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,ai_score,human_score,final_score,score_source,result_status,published_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,NULL,NULL,NULL,'PENDING',?,?,?)"
            + " ON DUPLICATE KEY UPDATE technical_verdict=VALUES(technical_verdict),"
            + " passed_test_cases=VALUES(passed_test_cases),total_test_cases=VALUES(total_test_cases),"
            + " time_ms=VALUES(time_ms),memory_kb=VALUES(memory_kb),result_status=VALUES(result_status),"
            + " published_at=VALUES(published_at),updated_at=VALUES(updated_at)",
        submissionId,
        result.verdict().name(),
        result.passedTestCases(),
        result.totalTestCases(),
        result.timeMs(),
        result.memoryKb(),
        internal ? "NEEDS_REVIEW" : "PENDING",
        now,
        now);
    jdbc.update(
        "UPDATE assessment_submission SET status=?,updated_at=?,version=version+1 WHERE id=?",
        internal ? "FAILED" : "JUDGED",
        now,
        submissionId);
    audit(
        submissionId,
        internal ? "OJ_JUDGE_FAILED" : "OJ_JUDGED",
        internal ? "Submission judge failed" : "Submission judged",
        internal ? "FAILED" : "SUCCESS",
        result.verdict().name(),
        now);
  }

  private void persistTestCaseResults(
      long submissionId,
      List<OjJudgeResult.TestCaseResult> results,
      List<OjSubmission.TestCase> testCases,
      Timestamp now) {
    jdbc.update("DELETE FROM oj_test_case_result WHERE submission_id=?", submissionId);
    for (var result : results) {
      int index = result.position() - 1;
      var testCase = index >= 0 && index < testCases.size() ? testCases.get(index) : null;
      jdbc.update(
          "INSERT INTO oj_test_case_result(submission_id,position,verdict,time_ms,memory_kb,safe_error,"
              + "input_preview,actual_output,expected_output,created_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?)",
          submissionId,
          result.position(),
          result.verdict().name(),
          result.timeMs(),
          result.memoryKb(),
          result.safeError(),
          testCase == null ? null : truncate(testCase.input()),
          truncate(result.actualOutput()),
          testCase == null ? null : truncate(testCase.expectedOutput()),
          now);
    }
  }

  /** 结果对照只保留前 2000 字符，避免把大输出写进接口响应和日志。 */
  private static String truncate(String value) {
    if (value == null) return null;
    return value.length() <= 2000 ? value : value.substring(0, 2000);
  }

  private void markFailed(long submissionId) {
    var now = Timestamp.from(clock.instant());
    List<OjSubmission.TestCase> testCases = assessmentTestCasesForSubmission(submissionId);
    tx.execute(
        status -> {
          jdbc.update(
              "UPDATE assessment_submission SET status='FAILED',updated_at=?,version=version+1 WHERE id=?",
              now,
              submissionId);
          jdbc.update(
              "INSERT INTO oj_judge_result(submission_id,verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,safe_error,created_at)"
                  + " VALUES(?, 'INTERNAL_ERROR',0,?,0,0,'synthetic judge queue unavailable',?)"
                  + " ON DUPLICATE KEY UPDATE verdict='INTERNAL_ERROR',passed_test_cases=0,"
                  + " total_test_cases=VALUES(total_test_cases),time_ms=0,memory_kb=0,"
                  + " safe_error=VALUES(safe_error),created_at=VALUES(created_at)",
              submissionId,
              testCases.size(),
              now);
          jdbc.update(
              "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,ai_score,human_score,final_score,score_source,result_status,published_at,updated_at)"
                  + " VALUES(?, 'INTERNAL_ERROR',0,?,0,0,NULL,NULL,NULL,'UNAVAILABLE','NEEDS_REVIEW',?,?)"
                  + " ON DUPLICATE KEY UPDATE technical_verdict='INTERNAL_ERROR',passed_test_cases=0,"
                  + " total_test_cases=VALUES(total_test_cases),time_ms=0,memory_kb=0,score_source='UNAVAILABLE',"
                  + " result_status='NEEDS_REVIEW',published_at=VALUES(published_at),updated_at=VALUES(updated_at)",
              submissionId,
              testCases.size(),
              now,
              now);
          persistTestCaseResults(submissionId, unavailableResults(testCases), testCases, now);
          audit(
              submissionId,
              "OJ_JUDGE_FAILED",
              "Submission judge unavailable",
              "FAILED",
              "INTERNAL_ERROR",
              now);
          return null;
        });
  }

  public OjJudgeResult practice(long problemId, String language, String code) {
    var rows =
        jdbc.queryForList(
            "SELECT judge_mode,function_name,parameter_types,return_type FROM assessment_problem WHERE id=?",
            problemId);
    boolean functionMode = !rows.isEmpty() && "FUNCTION".equals(rows.getFirst().get("judge_mode"));
    var signature = functionMode ? signature(rows.getFirst()) : null;
    List<OjSubmission.TestCase> testCases =
        problemTestCases(problemId, "assessment_problem_practice_test_case", signature);
    try {
      return functionMode
          ? judgeFunction(signature, language, code, testCases)
          : adapter.judge(new OjSubmission(language, code, testCases));
    } catch (RuntimeException error) {
      return internalResult(testCases);
    }
  }

  private List<OjSubmission.TestCase> assessmentTestCases(
      long problemId, FunctionHarness.Signature signature) {
    return problemTestCases(problemId, "assessment_problem_assessment_test_case", signature);
  }

  private List<OjSubmission.TestCase> problemTestCases(
      long problemId, String table, FunctionHarness.Signature signature) {
    var rows =
        jdbc.queryForList(
            "SELECT input,expected_output FROM " + table
                + " WHERE problem_id=? ORDER BY position",
            problemId);
    var result = new ArrayList<OjSubmission.TestCase>();
    for (var row : rows) {
      String expected = String.valueOf(row.get("expected_output"));
      result.add(
          new OjSubmission.TestCase(
              String.valueOf(row.get("input")),
              signature == null
                  ? expected
                  : FunctionHarness.canonicalExpected(expected, signature.returns())));
    }
    return result;
  }

  private List<OjSubmission.TestCase> assessmentTestCasesForSubmission(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT t.input,t.expected_output,p.judge_mode,p.return_type FROM assessment_submission s"
                + " JOIN assessment_problem_assessment_test_case t ON t.problem_id=s.problem_id"
                + " JOIN assessment_problem p ON p.id=s.problem_id"
                + " WHERE s.id=? ORDER BY t.position",
            submissionId);
    var result = new ArrayList<OjSubmission.TestCase>();
    for (var row : rows) {
      String expected = String.valueOf(row.get("expected_output"));
      boolean functionMode = "FUNCTION".equals(row.get("judge_mode"));
      result.add(
          new OjSubmission.TestCase(
              String.valueOf(row.get("input")),
              functionMode
                  ? FunctionHarness.canonicalExpected(
                      expected, FunctionHarness.ValueType.parse(String.valueOf(row.get("return_type"))))
                  : expected));
    }
    return result;
  }

  private static OjJudgeResult internalResult(List<OjSubmission.TestCase> testCases) {
    return new OjJudgeResult(
        OjVerdict.INTERNAL_ERROR,
        0,
        testCases.size(),
        0,
        0,
        "synthetic internal judge failure",
        unavailableResults(testCases));
  }

  private static List<OjJudgeResult.TestCaseResult> unavailableResults(
      List<OjSubmission.TestCase> testCases) {
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    for (int index = 0; index < testCases.size(); index++) {
      results.add(
          new OjJudgeResult.TestCaseResult(
              index + 1, OjVerdict.INTERNAL_ERROR, 0, 0, "synthetic judge unavailable"));
    }
    return results;
  }

  private void audit(
      long submissionId,
      String action,
      String summary,
      String result,
      String verdict,
      Timestamp now) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,detail,summary,result,occurred_at)"
            + " VALUES(NULL,?,'SUBMISSION',?,?,?,?,?)",
        action,
        submissionId,
        "{\"verdict\":\"" + verdict + "\"}",
        summary,
        result,
        now);
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }
}
