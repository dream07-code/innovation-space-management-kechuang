package io.github.dream07code.innovationspace.scoring;

import io.github.dream07code.innovationspace.service.PointLedgerService;
import io.github.dream07code.innovationspace.points.PointsPolicyProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScoringWorker {
  private record TaskContext(long taskId, long reportId, long versionId, long memberId) {}

  private final JdbcTemplate jdbc;
  private final ScoringProviderRegistry registry;
  private final PointLedgerService points;
  private final ScoringProperties properties;
  private final PointsPolicyProperties pointsPolicy;
  private final Clock clock;
  private final TransactionTemplate tx;

  public ScoringWorker(
      JdbcTemplate jdbc,
      ScoringProviderRegistry registry,
      PointLedgerService points,
      ScoringProperties properties,
      PointsPolicyProperties pointsPolicy,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.registry = registry;
    this.points = points;
    this.properties = properties;
    this.pointsPolicy = pointsPolicy;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public boolean processOne() {
    recoverExpiredLeases();
    var claims =
        jdbc.queryForList(
            "SELECT * FROM report_scoring_task WHERE status IN ('QUEUED','RETRYING')"
                + " AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY id LIMIT 1",
            Timestamp.from(clock.instant()));
    if (claims.isEmpty()) return false;
    long taskId = ((Number) claims.getFirst().get("id")).longValue();
    String oldStatus = (String) claims.getFirst().get("status");
    int changed =
        jdbc.update(
            "UPDATE report_scoring_task SET status='PROCESSING',started_at=COALESCE(started_at,?),updated_at=?"
                + " WHERE id=? AND status=?",
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()),
            taskId,
            oldStatus);
    if (changed == 0) return true;
    TaskContext context = context(taskId);
    if (!currentCycle(context)) {
      transition(taskId, "STALE", null, null, null);
      return true;
    }
    try {
      var score = registry.provider().score(loadInput(context));
      score.validate();
      tx.executeWithoutResult(
          status -> {
            jdbc.queryForMap(
                "SELECT membership_status,membership_started_at FROM member WHERE id=? FOR UPDATE",
                context.memberId());
            var task = jdbc.queryForMap("SELECT * FROM report_scoring_task WHERE id=? FOR UPDATE", taskId);
            if (!"PROCESSING".equals(task.get("status"))) return;
            var current =
                jdbc.queryForMap(
                    "SELECT r.id,r.member_id,r.score_status,r.first_submitted_at,m.membership_started_at,m.membership_status"
                        + " FROM report r JOIN member m ON m.id=r.member_id"
                        + " JOIN report_version v ON v.report_id=r.id WHERE v.id=? AND v.is_current=TRUE",
                    context.versionId());
            if (!eligible(current)) {
              transition(taskId, "STALE", null, null, null);
              return;
            }
            int currentPoints =
                jdbc.queryForObject(
                    "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?",
                    Integer.class,
                    context.reportId());
            int currentAiPoints =
                points.sourceNet(context.memberId(), context.reportId(), "AI_ADJUSTMENT");
            int delta =
                "BASE_PLUS_AI".equals(pointsPolicy.getPointsMode())
                    ? score.score() - currentAiPoints
                    : score.score() - currentPoints;
            if (currentPoints + delta > pointsPolicy.getMaxFinalScore())
              throw new IllegalArgumentException("Final score exceeds configured maximum");
            Instant effectiveAt = instant(current.get("first_submitted_at"));
            if (delta != 0)
              points.appendIfAbsent(
                  context.memberId(),
                  context.reportId(),
                  context.versionId(),
                  delta,
                  "AI_ADJUSTMENT",
                  "AI_SCORE:" + taskId + ":" + score.score(),
                  effectiveAt);
            jdbc.update(
                "UPDATE report SET score_status='SCORED',final_score=?,score_reason=?,rubric_version=?,updated_at=? WHERE id=?",
                score.score(),
                score.reason(),
                ScoringRubric.VERSION,
                Timestamp.from(clock.instant()),
                context.reportId());
            transition(
                taskId,
                "SCORED",
                score.score(),
                score.reason(),
                score.provider() + "/" + score.model());
          });
      return true;
    } catch (Exception error) {
      handleFailure(context, error);
      return true;
    }
  }

  private TaskContext context(long taskId) {
    var row =
        jdbc.queryForMap(
            "SELECT t.id,t.report_id,t.report_version_id,r.member_id FROM report_scoring_task t"
                + " JOIN report r ON r.id=t.report_id WHERE t.id=?",
            taskId);
    return new TaskContext(
        taskId,
        ((Number) row.get("report_id")).longValue(),
        ((Number) row.get("report_version_id")).longValue(),
        ((Number) row.get("member_id")).longValue());
  }

  private boolean currentCycle(TaskContext context) {
    var rows =
        jdbc.queryForList(
            "SELECT r.first_submitted_at,m.membership_started_at,m.membership_status"
                + " FROM report r JOIN member m ON m.id=r.member_id"
                + " JOIN report_version v ON v.report_id=r.id WHERE v.id=? AND v.is_current=TRUE",
            context.versionId());
    return !rows.isEmpty() && eligible(rows.getFirst());
  }

  private static boolean eligible(java.util.Map<String, Object> row) {
    return "ACTIVE".equals(row.get("membership_status"))
        && !"MANUAL".equals(row.get("score_status") == null ? null : row.get("score_status"))
        && instant(row.get("first_submitted_at"))
            .compareTo(instant(row.get("membership_started_at"))) >= 0;
  }

  private ScoringInput loadInput(TaskContext context) {
    var row =
        jdbc.queryForMap(
            "SELECT r.report_type,v.content_json FROM report r"
                + " JOIN report_version v ON v.id=? WHERE r.id=?",
            context.versionId(),
            context.reportId());
    return new ScoringInput(
        (String) row.get("report_type"),
        String.valueOf(context.versionId()),
        ScoringRubric.VERSION,
        String.valueOf(row.get("content_json")));
  }

  private void handleFailure(TaskContext context, Exception error) {
    tx.executeWithoutResult(
        status -> {
          jdbc.queryForMap(
              "SELECT membership_status,membership_started_at FROM member WHERE id=? FOR UPDATE",
              context.memberId());
          var task =
              jdbc.queryForMap("SELECT * FROM report_scoring_task WHERE id=? FOR UPDATE", context.taskId());
          if (!"PROCESSING".equals(task.get("status"))) return;
          if (!currentCycle(context)) {
            transition(context.taskId(), "STALE", null, null, null);
            return;
          }
          int attempt = ((Number) task.get("attempt")).intValue() + 1;
          String state = attempt > properties.getMaxRetries() ? "REVIEW_REQUIRED" : "RETRYING";
          jdbc.update(
              "UPDATE report_scoring_task SET status=?,attempt=?,safe_error_code=?,next_retry_at=?,finished_at=?,updated_at=? WHERE id=?",
              state,
              attempt,
              safeCode(error),
              state.equals("RETRYING")
                  ? Timestamp.from(clock.instant().plusSeconds((long) Math.pow(2, attempt)))
                  : null,
              state.equals("RETRYING") ? null : Timestamp.from(clock.instant()),
              Timestamp.from(clock.instant()),
              context.taskId());
        });
  }

  private void recoverExpiredLeases() {
    Instant now = clock.instant();
    Instant cutoff =
        now.minusMillis((long) properties.getConnectTimeoutMs() + properties.getRequestTimeoutMs() + 5000L);
    jdbc.update(
        "UPDATE report_scoring_task SET status=CASE WHEN attempt>=? THEN 'REVIEW_REQUIRED' ELSE 'RETRYING' END,"
            + "safe_error_code='LEASE_EXPIRED',next_retry_at=?,finished_at=CASE WHEN attempt>=? THEN ? ELSE NULL END,updated_at=?"
            + " WHERE status='PROCESSING' AND updated_at<?",
        properties.getMaxRetries(),
        Timestamp.from(now),
        properties.getMaxRetries(),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(cutoff));
  }

  private void transition(long id, String state, Integer score, String reason, String metadata) {
    jdbc.update(
        "UPDATE report_scoring_task SET status=?,score=?,reason=?,finished_at=COALESCE(finished_at,?),updated_at=?,provider=? WHERE id=?",
        state,
        score,
        reason,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()),
        metadata,
        id);
  }

  private static String safeCode(Exception error) {
    String text = error.toString().toLowerCase(Locale.ROOT);
    if (text.contains("timeout")) return "TIMEOUT";
    if (text.contains("json") || text.contains("validation")) return "INVALID_RESPONSE";
    return "PROVIDER_ERROR";
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
    if (value instanceof Instant instant) return instant;
    throw new IllegalStateException("Unsupported timestamp");
  }
}
