package io.github.dream07code.innovationspace.scoring;

import io.github.dream07code.innovationspace.points.PointLedgerService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ScoringWorker {
  private final JdbcTemplate jdbc;
  private final ScoringProviderRegistry registry;
  private final PointLedgerService points;
  private final ScoringProperties properties;
  private final Clock clock;
  private final TransactionTemplate tx;
  public ScoringWorker(JdbcTemplate jdbc, ScoringProviderRegistry registry, PointLedgerService points, ScoringProperties properties,
      Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc; this.registry = registry; this.points = points; this.clock = clock;
    this.properties = properties;
    this.tx = new TransactionTemplate(manager);
  }
  public boolean processOne() {
    var claims = jdbc.queryForList(
        "SELECT * FROM report_scoring_task WHERE status IN ('QUEUED','RETRYING') AND (next_retry_at IS NULL OR next_retry_at <= ?) ORDER BY id LIMIT 1",
        Timestamp.from(clock.instant()));
    if (claims.isEmpty()) return false;
    var claim = claims.getFirst();
    long id = ((Number) claim.get("id")).longValue();
    String oldStatus = (String) claim.get("status");
    int changed = jdbc.update("UPDATE report_scoring_task SET status='PROCESSING',started_at=COALESCE(started_at,?),updated_at=? WHERE id=? AND status=?",
        Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), id, oldStatus);
    if (changed == 0) return true;
    try {
      var score = registry.provider().score(loadInput(id));
      score.validate();
      tx.executeWithoutResult(s -> {
        var task = jdbc.queryForMap("SELECT * FROM report_scoring_task WHERE id=? FOR UPDATE", id);
        if (!"PROCESSING".equals(task.get("status"))) return;
        var version = task.get("report_version_id");
        var current = jdbc.queryForList("SELECT r.* FROM report r JOIN report_version v ON v.report_id=r.id WHERE v.id=? AND v.is_current=TRUE", version);
        if (current.isEmpty()) { transition(id, "STALE", null, null, null); return; }
        if ("MANUAL".equals(current.getFirst().get("score_status"))) {
          transition(id, "STALE", null, null, null);
          return;
        }
        long reportId = ((Number) current.getFirst().get("id")).longValue();
        long memberId = ((Number) current.getFirst().get("member_id")).longValue();
        int base = jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=? AND source='SUBMISSION_BASE'", Integer.class, reportId);
        int delta = score.score() - base;
        if (delta != 0) points.appendIfAbsent(memberId, reportId, ((Number) version).longValue(), delta, "AI_ADJUSTMENT",
            "AI_SCORE:" + id + ":" + score.score(), clock.instant());
        jdbc.update("UPDATE report SET score_status='SCORED',final_score=?,score_reason=?,updated_at=? WHERE id=?",
            score.score(), score.reason(), Timestamp.from(clock.instant()), reportId);
        transition(id, "SCORED", score.score(), score.reason(), score.provider() + "/" + score.model());
      });
      return true;
    } catch (Exception e) {
      int attempt = ((Number) claim.get("attempt")).intValue() + 1;
      String state = attempt > properties.getMaxRetries() ? "REVIEW_REQUIRED" : "RETRYING";
      jdbc.update("UPDATE report_scoring_task SET status=?,attempt=?,safe_error_code=?,next_retry_at=?,finished_at=?,updated_at=? WHERE id=?",
          state, attempt, safeCode(e), state.equals("RETRYING") ? Timestamp.from(clock.instant().plusSeconds((long) Math.pow(2, attempt))) : null,
          state.equals("RETRYING") ? null : Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), id);
      return true;
    }
  }
  private ScoringInput loadInput(long id) {
    var row = jdbc.queryForMap("SELECT r.report_type,t.report_version_id,v.content_json FROM report_scoring_task t JOIN report_version v ON v.id=t.report_version_id JOIN report r ON r.id=t.report_id WHERE t.id=?", id);
    return new ScoringInput(
        (String) row.get("report_type"),
        String.valueOf(row.get("report_version_id")),
        "v1",
        String.valueOf(row.get("content_json")));
  }
  private void transition(long id, String state, Integer score, String reason, String metadata) {
    jdbc.update("UPDATE report_scoring_task SET status=?,score=?,reason=?,finished_at=COALESCE(finished_at,?),updated_at=?,provider=? WHERE id=?",
        state, score, reason, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), metadata, id);
  }
  private static String safeCode(Exception e) {
    String text = e.toString().toLowerCase(Locale.ROOT);
    if (text.contains("timeout")) return "TIMEOUT";
    if (text.contains("json") || text.contains("validation")) return "INVALID_RESPONSE";
    return "PROVIDER_ERROR";
  }
}
