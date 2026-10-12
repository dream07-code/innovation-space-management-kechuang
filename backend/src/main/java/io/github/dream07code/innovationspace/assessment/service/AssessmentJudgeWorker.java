package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssessmentJudgeWorker {
  private final JdbcTemplate jdbc;
  private final OjAdapter adapter;
  private final Clock clock;
  private final ThreadPoolExecutor executor;

  public AssessmentJudgeWorker(JdbcTemplate jdbc, OjAdapter adapter, Clock clock) {
    this.jdbc = jdbc;
    this.adapter = adapter;
    this.clock = clock;
    this.executor =
        new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            java.util.concurrent.Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void enqueue(long submissionId) {
    try {
      executor.execute(() -> judge(submissionId));
    } catch (java.util.concurrent.RejectedExecutionException error) {
      markFailed(submissionId);
    }
  }

  private void judge(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT s.language,s.status,c.code_text FROM assessment_submission s JOIN"
                + " assessment_submission_code c ON c.submission_id=s.id WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    if (!"RECEIVED".equals(row.get("status")) && !"JUDGING".equals(row.get("status"))) return;
    jdbc.update(
        "UPDATE assessment_submission SET status='JUDGING',updated_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        submissionId);
    OjJudgeResult result;
    try {
      Object languageValue = row.get("language");
      Object codeValue = row.get("code_text");
      String code =
          codeValue instanceof String text
              ? text
              : new String((byte[]) codeValue, java.nio.charset.StandardCharsets.UTF_8);
      result = adapter.judge(new OjSubmission((String) languageValue, code));
    } catch (RuntimeException error) {
      result =
          new OjJudgeResult(
              OjVerdict.INTERNAL_ERROR, 0, 0, 0, 0, "synthetic internal judge failure");
    }
    persist(submissionId, result);
  }

  private void persist(long submissionId, OjJudgeResult result) {
    var now = Timestamp.from(clock.instant());
    boolean internal = result.verdict() == OjVerdict.INTERNAL_ERROR;
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

  private void markFailed(long submissionId) {
    var now = Timestamp.from(clock.instant());
    jdbc.update(
        "UPDATE assessment_submission SET status='FAILED',updated_at=?,version=version+1 WHERE id=?",
        now,
        submissionId);
    jdbc.update(
        "INSERT INTO oj_judge_result(submission_id,verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,safe_error,created_at)"
            + " VALUES(?, 'INTERNAL_ERROR',0,0,0,0,'synthetic judge queue unavailable',?)"
            + " ON DUPLICATE KEY UPDATE verdict='INTERNAL_ERROR',safe_error=VALUES(safe_error),created_at=VALUES(created_at)",
        submissionId,
        now);
    jdbc.update(
        "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,ai_score,human_score,final_score,score_source,result_status,published_at,updated_at)"
            + " VALUES(?, 'INTERNAL_ERROR',0,0,0,0,NULL,NULL,NULL,'UNAVAILABLE','NEEDS_REVIEW',?,?)"
            + " ON DUPLICATE KEY UPDATE technical_verdict='INTERNAL_ERROR',score_source='UNAVAILABLE',"
            + " result_status='NEEDS_REVIEW',published_at=VALUES(published_at),updated_at=VALUES(updated_at)",
        submissionId,
        now,
        now);
    audit(
        submissionId,
        "OJ_JUDGE_FAILED",
        "Submission judge unavailable",
        "FAILED",
        "INTERNAL_ERROR",
        now);
  }

  private void audit(
      long submissionId,
      String action,
      String summary,
      String result,
      String verdict,
      java.sql.Timestamp now) {
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
