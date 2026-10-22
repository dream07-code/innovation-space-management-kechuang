package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
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

@Service
public class AssessmentJudgeWorker {
  private static final Logger log = LoggerFactory.getLogger(AssessmentJudgeWorker.class);

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
            "SELECT s.language,s.status,s.problem_id,c.code_text FROM assessment_submission s JOIN"
                + " assessment_submission_code c ON c.submission_id=s.id WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    if (!"RECEIVED".equals(row.get("status")) && !"JUDGING".equals(row.get("status"))) return;
    jdbc.update(
        "UPDATE assessment_submission SET status='JUDGING',updated_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        submissionId);

    List<OjSubmission.TestCase> testCases =
        assessmentTestCases(((Number) row.get("problem_id")).longValue());
    OjJudgeResult result;
    try {
      Object languageValue = row.get("language");
      Object codeValue = row.get("code_text");
      String code =
          codeValue instanceof String text
              ? text
              : new String((byte[]) codeValue, StandardCharsets.UTF_8);
      result = adapter.judge(new OjSubmission((String) languageValue, code, testCases));
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
    persist(submissionId, result);
    evaluations.evaluateAfterJudge(submissionId);
  }

  private void persist(long submissionId, OjJudgeResult result) {
    var now = Timestamp.from(clock.instant());
    boolean internal = result.verdict() == OjVerdict.INTERNAL_ERROR;
    tx.execute(
        status -> {
          persistJudgeSummary(submissionId, result, internal, now);
          persistTestCaseResults(submissionId, result.testCaseResults(), now);
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
      long submissionId, List<OjJudgeResult.TestCaseResult> results, Timestamp now) {
    jdbc.update("DELETE FROM oj_test_case_result WHERE submission_id=?", submissionId);
    for (var result : results) {
      jdbc.update(
          "INSERT INTO oj_test_case_result(submission_id,position,verdict,time_ms,memory_kb,safe_error,created_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          submissionId,
          result.position(),
          result.verdict().name(),
          result.timeMs(),
          result.memoryKb(),
          result.safeError(),
          now);
    }
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
          persistTestCaseResults(submissionId, unavailableResults(testCases), now);
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
    List<OjSubmission.TestCase> testCases =
        problemTestCases(problemId, "assessment_problem_practice_test_case");
    try {
      return adapter.judge(new OjSubmission(language, code, testCases));
    } catch (RuntimeException error) {
      return internalResult(testCases);
    }
  }

  private List<OjSubmission.TestCase> assessmentTestCases(long problemId) {
    return problemTestCases(problemId, "assessment_problem_assessment_test_case");
  }

  private List<OjSubmission.TestCase> problemTestCases(long problemId, String table) {
    var rows =
        jdbc.queryForList(
            "SELECT input,expected_output FROM " + table
                + " WHERE problem_id=? ORDER BY position",
            problemId);
    var result = new ArrayList<OjSubmission.TestCase>();
    for (var row : rows) {
      result.add(
          new OjSubmission.TestCase(
              String.valueOf(row.get("input")), String.valueOf(row.get("expected_output"))));
    }
    return result;
  }

  private List<OjSubmission.TestCase> assessmentTestCasesForSubmission(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT t.input,t.expected_output FROM assessment_submission s"
                + " JOIN assessment_problem_assessment_test_case t ON t.problem_id=s.problem_id"
                + " WHERE s.id=? ORDER BY t.position",
            submissionId);
    var result = new ArrayList<OjSubmission.TestCase>();
    for (var row : rows) {
      result.add(
          new OjSubmission.TestCase(
              String.valueOf(row.get("input")), String.valueOf(row.get("expected_output"))));
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
