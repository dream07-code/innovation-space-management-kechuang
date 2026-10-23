package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.assessment.service.AssessmentAiProvider;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import io.github.dream07code.innovationspace.assessment.*;
import io.github.dream07code.innovationspace.assessment.service.AssessmentAiProviderRegistry;


import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssessmentEvaluationServiceImpl implements AssessmentEvaluationService {
  private static final Logger log = LoggerFactory.getLogger(AssessmentEvaluationServiceImpl.class);
  private final JdbcTemplate jdbc;
  private final AssessmentAiProviderRegistry providers;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public AssessmentEvaluationServiceImpl(
      JdbcTemplate jdbc,
      AssessmentAiProviderRegistry providers,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.providers = providers;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public synchronized Map<String, Object> get(long submissionId, SessionPrincipal actor) {
    requireVisible(submissionId, actor);
    ensurePending(submissionId);
    var latest = latest(submissionId);
    if ("PENDING".equals(latest.get("status"))) {
      var judge = judge(submissionId);
      if (judge != null && providers.enabled()) {
        evaluate(submissionId, judge);
        latest = latest(submissionId);
      }
    }
    refreshResult(submissionId);
    return response(latest);
  }

  public synchronized void evaluateAfterJudge(long submissionId) {
    ensurePending(submissionId);
    var judge = judge(submissionId);
    if (judge != null && providers.enabled()) evaluate(submissionId, judge);
    refreshResult(submissionId);
  }

  public synchronized void refreshResult(long submissionId) {
    var judge = judge(submissionId);
    if (judge == null) return;
    retryLocalFailure(submissionId, judge);
    var ai = latest(submissionId);
    var review =
        jdbc.queryForList(
            "SELECT score,status FROM assessment_review WHERE submission_id=? AND status='ACTIVE'"
                + " ORDER BY version DESC LIMIT 1",
            submissionId);
    Integer humanScore =
        review.isEmpty() ? null : ((Number) review.getFirst().get("score")).intValue();
    Integer aiScore = ai.get("score") == null ? null : ((Number) ai.get("score")).intValue();
    String aiStatus = (String) ai.get("status");
    String scoreSource;
    String resultStatus;
    Integer finalScore;
    if (humanScore != null) {
      scoreSource = "HUMAN";
      resultStatus = "COMPLETE";
      finalScore = humanScore;
    } else if ("COMPLETE".equals(aiStatus) && aiScore != null) {
      scoreSource = "AI";
      resultStatus = "COMPLETE";
      finalScore = aiScore;
    } else if ("FAILED".equals(aiStatus)) {
      scoreSource = "UNAVAILABLE";
      resultStatus = "NEEDS_REVIEW";
      finalScore = null;
    } else {
      scoreSource = "PENDING";
      resultStatus = judge.verdict() == OjVerdict.INTERNAL_ERROR ? "NEEDS_REVIEW" : "PENDING";
      finalScore = null;
    }
    var now = Timestamp.from(clock.instant());
    int updated =
        jdbc.update(
            "UPDATE assessment_result SET technical_verdict=?,passed_test_cases=?,total_test_cases=?,"
                + "time_ms=?,memory_kb=?,ai_score=?,human_score=?,final_score=?,score_source=?,"
                + "result_status=?,published_at=COALESCE(published_at,?),updated_at=? WHERE submission_id=?",
            judge.verdict().name(),
            judge.passedTestCases(),
            judge.totalTestCases(),
            judge.timeMs(),
            judge.memoryKb(),
            aiScore,
            humanScore,
            finalScore,
            scoreSource,
            resultStatus,
            now,
            now,
            submissionId);
    if (updated == 0) {
      jdbc.update(
          "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,"
              + "time_ms,memory_kb,ai_score,human_score,final_score,score_source,result_status,published_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          submissionId,
          judge.verdict().name(),
          judge.passedTestCases(),
          judge.totalTestCases(),
          judge.timeMs(),
          judge.memoryKb(),
          aiScore,
          humanScore,
          finalScore,
          scoreSource,
          resultStatus,
          now,
          now);
    }
  }

  /**
   * 本地提供方（mock / heuristic）是纯计算，失败通常是之前配置了不可用的外部提供方。
   * 切到本地提供方后，读取结果时自动补算一次，避免历史记录一直停在“暂不可用”；
   * 外部提供方不自动重试，以免产生不可控的调用费用。
   */
  private void retryLocalFailure(long submissionId, OjJudgeResult judge) {
    if (!providers.enabled() || !providers.local()) return;
    var rows =
        jdbc.queryForList(
            "SELECT status FROM ai_evaluation WHERE submission_id=? ORDER BY version DESC LIMIT 1",
            submissionId);
    if (rows.isEmpty() || !"FAILED".equals(rows.getFirst().get("status"))) return;
    log.info("Retrying local AI evaluation for submission {}", submissionId);
    evaluate(submissionId, judge);
  }

  private void evaluate(long submissionId, OjJudgeResult judge) {
    var rows =
        jdbc.queryForList(
            "SELECT s.language,c.code_text FROM assessment_submission s JOIN assessment_submission_code c"
                + " ON c.submission_id=s.id WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    Object codeValue = row.get("code_text");
    String code =
        codeValue instanceof String text
            ? text
            : new String((byte[]) codeValue, java.nio.charset.StandardCharsets.UTF_8);
    AssessmentAiProvider.AssessmentAiScore score;
    try {
      score =
          providers
              .provider()
              .evaluate(
                  new AssessmentAiProvider.AssessmentAiInput(
                      String.valueOf(submissionId), (String) row.get("language"), code, judge));
    } catch (RuntimeException error) {
      String reason = failureReason(error);
      log.warn("AI evaluation failed for submission {}: {}", submissionId, reason);
      insert(submissionId, "FAILED", null, reason, null);
      return;
    }
    insert(submissionId, "COMPLETE", score.score(), score.reason(), score.model());
  }

  /**
   * 只保留异常类型与一句可读原因（例如 "AI provider HTTP 401"），
   * 便于在结果页看到“暂不可用”到底卡在哪，且不含源码、密钥或请求体。
   */
  private static String failureReason(RuntimeException error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
    String text = message.replaceAll("\\s+", " ").trim();
    if (text.length() > 300) text = text.substring(0, 300);
    return "AI 评分调用失败：" + text;
  }

  private void insert(long submissionId, String status, Integer score, String reason, String model) {
    var now = Timestamp.from(clock.instant());
    int next =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM ai_evaluation WHERE submission_id=?",
            Integer.class,
            submissionId);
    jdbc.update(
        "INSERT INTO ai_evaluation(submission_id,version,status,score,reason,model,rubric_version,created_at)"
            + " VALUES(?,?,?,?,?,?, 'assessment-rubric-v1',?)",
        submissionId,
        next,
        status,
        score,
        reason,
        model,
        now);
  }

  private void ensurePending(long submissionId) {
    var count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ai_evaluation WHERE submission_id=?", Integer.class, submissionId);
    if (count == 0) {
      jdbc.update(
          "INSERT INTO ai_evaluation(submission_id,version,status,score,reason,model,rubric_version,created_at)"
              + " VALUES(?,1,'PENDING',NULL,NULL,NULL,'assessment-rubric-v1',?)",
          submissionId,
          Timestamp.from(clock.instant()));
    }
  }

  private Map<String, Object> latest(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ai_evaluation WHERE submission_id=? ORDER BY version DESC LIMIT 1",
            submissionId);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private OjJudgeResult judge(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM oj_judge_result WHERE submission_id=?", submissionId);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    return new OjJudgeResult(
        OjVerdict.valueOf((String) row.get("verdict")),
        ((Number) row.get("passed_test_cases")).intValue(),
        ((Number) row.get("total_test_cases")).intValue(),
        ((Number) row.get("time_ms")).intValue(),
        ((Number) row.get("memory_kb")).intValue(),
        (String) row.get("safe_error"));
  }

  private Map<String, Object> response(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("submissionId", String.valueOf(row.get("submission_id")));
    out.put("status", row.get("status"));
    out.put("score", row.get("score"));
    out.put("reason", row.get("reason"));
    out.put("model", row.get("model"));
    out.put("rubricVersion", row.get("rubric_version"));
    out.put("createdAt", time(row.get("created_at")));
    return out;
  }

  private void requireVisible(long submissionId, SessionPrincipal actor) {
    var rows =
        jdbc.queryForList(
            "SELECT s.member_id,a.direction_id FROM assessment_submission s JOIN assessment a"
                + " ON a.id=s.assessment_id WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    var row = rows.getFirst();
    long memberId = ((Number) row.get("member_id")).longValue();
    long directionId = ((Number) row.get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if ("STUDENT".equals(actor.role()) && actor.memberId() != null && actor.memberId() == memberId) return;
    if ("DIRECTION_LEADER".equals(actor.role()) && actor.managedDirectionIds().contains(directionId)) return;
    throw new ApiException(404, ErrorCode.NOT_FOUND);
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
}
