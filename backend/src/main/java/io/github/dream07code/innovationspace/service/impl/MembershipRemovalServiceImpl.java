package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.MembershipRemovalService;
import io.github.dream07code.innovationspace.membership.*;
import io.github.dream07code.innovationspace.service.PointLedgerService;


import io.github.dream07code.innovationspace.service.PointLedgerService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MembershipRemovalServiceImpl implements MembershipRemovalService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final PointLedgerService points;
  private final InactivityPolicy policy;
  private final Clock clock;
  private final MembershipRemovalProperties properties;
  private final TransactionTemplate tx;

  public MembershipRemovalServiceImpl(
      JdbcTemplate jdbc,
      PointLedgerService points,
      InactivityPolicy policy,
      Clock clock,
      MembershipRemovalProperties properties,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.points = points;
    this.policy = policy;
    this.clock = clock;
    this.properties = properties;
    this.tx = new TransactionTemplate(manager);
  }

  public int removeDueMembers() {
    int removed = 0;
    for (Map<String, Object> candidate : candidates(null, properties.getRemovalBatchSize())) {
      long memberId = ((Number) candidate.get("id")).longValue();
      try {
        if (removeDueMember(memberId)) removed++;
      } catch (Exception failure) {
        recordFailureSafely(memberId);
      }
    }
    return removed;
  }

  public boolean removeDueMember(long memberId) {
    if (candidates(memberId, 1).isEmpty()) return false;
    Instant now = clock.instant();
    LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    Boolean removed =
        tx.execute(
            status -> {
              Long userId =
                  jdbc.queryForObject(
                      "SELECT id FROM app_user WHERE member_id=? FOR UPDATE", Long.class, memberId);
              var locked =
                  jdbc.queryForMap(
                      "SELECT id,membership_started_at FROM member WHERE id=? AND membership_status='ACTIVE' FOR UPDATE",
                      memberId);
              Instant membershipStarted = instant(locked.get("membership_started_at"));
              Instant lastSubmitted = latestDailySubmitted(memberId, membershipStarted);
              var evaluation =
                  policy.evaluate(
                      businessDate,
                      localDate(locked.get("membership_started_at")),
                      lastSubmitted == null ? null : localDate(Timestamp.from(lastSubmitted)));
              if (!evaluation.removable()) return false;
              int changed =
                  jdbc.update(
                      "UPDATE member SET membership_status='REMOVED',removed_at=?,removal_reason=?,"
                          + "version=version+1,updated_at=? WHERE id=? AND membership_status='ACTIVE'",
                      Timestamp.from(now),
                      "TWO_WEEKS_NO_DAILY_REPORT",
                      Timestamp.from(now),
                      memberId);
              if (changed == 0) return false;
              jdbc.update(
                  "UPDATE report_scoring_task t JOIN report r ON r.id=t.report_id"
                      + " SET t.status='STALE',t.safe_error_code='MEMBER_REMOVED',t.finished_at=?,t.updated_at=?"
                      + " WHERE r.member_id=? AND t.status IN ('QUEUED','PROCESSING','RETRYING')",
                  Timestamp.from(now),
                  Timestamp.from(now),
                  memberId);
              points.reverseNonZeroEntries(memberId, InactivityPolicy.RULE_VERSION, now);
              jdbc.update("UPDATE app_user SET auth_revision=auth_revision+1 WHERE id=?", userId);
              jdbc.update(
                  "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason=? WHERE user_id=?",
                  Timestamp.from(now),
                  "MEMBER_AUTO_REMOVED",
                  userId);
              jdbc.update(
                  "INSERT INTO audit_log(actor_user_id,action,resource_type,resource_id,reason,summary,result,detail,occurred_at)"
                      + " VALUES(NULL,'MEMBER_AUTO_REMOVED','MEMBER',?,?,'Removed inactive student','SUCCESS',JSON_OBJECT("
                      + "'reasonCode',?,'membershipStartedAt',?,'lastDailySubmittedAt',?,'completedInactiveDays',?,'thresholdDays',?,'ruleVersion',?),?)",
                  memberId,
                  "TWO_WEEKS_NO_DAILY_REPORT",
                  "TWO_WEEKS_NO_DAILY_REPORT",
                  iso(membershipStarted),
                  lastSubmitted == null ? null : iso(lastSubmitted),
                  evaluation.completedInactiveDays(),
                  InactivityPolicy.THRESHOLD_DAYS,
                  InactivityPolicy.RULE_VERSION,
                  Timestamp.from(now));
              return true;
            });
    return Boolean.TRUE.equals(removed);
  }

  private Instant latestDailySubmitted(long memberId, Instant membershipStarted) {
    var rows =
        jdbc.queryForList(
            "SELECT first_submitted_at FROM report"
                + " WHERE member_id=? AND report_type='DAILY' AND status='SUBMITTED'"
                + " AND first_submitted_at>=? ORDER BY first_submitted_at DESC LIMIT 1 FOR UPDATE",
            memberId,
            Timestamp.from(membershipStarted));
    return rows.isEmpty() || rows.getFirst().get("first_submitted_at") == null
        ? null
        : instant(rows.getFirst().get("first_submitted_at"));
  }

  private void recordFailureSafely(long memberId) {
    try {
      jdbc.update(
          "INSERT INTO audit_log(actor_user_id,action,resource_type,resource_id,reason,summary,result,detail,occurred_at)"
              + " VALUES(NULL,'MEMBER_AUTO_REMOVAL_FAILED','MEMBER',?,'MEMBER_AUTO_REMOVAL_FAILED',"
              + "'Skipped one member after failure','FAILURE',JSON_OBJECT('reasonCode',?),?)",
          memberId,
          "MEMBER_AUTO_REMOVAL_FAILED",
          Timestamp.from(clock.instant()));
    } catch (Exception ignored) {
      // Database-wide failures remain retryable on the next scheduled run.
    }
  }

  private List<Map<String, Object>> candidates(Long memberId, int limit) {
    String where =
        " WHERE m.membership_status='ACTIVE' AND u.role='STUDENT'"
            + (memberId == null ? "" : " AND m.id=?");
    String sql =
        "SELECT m.id,u.id user_id,m.membership_started_at,"
            + "MAX(CASE WHEN r.report_type='DAILY' AND r.status='SUBMITTED'"
            + " AND r.first_submitted_at>=m.membership_started_at THEN r.first_submitted_at END)"
            + " last_daily_submitted_at FROM member m"
            + " JOIN app_user u ON u.member_id=m.id"
            + " LEFT JOIN report r ON r.member_id=m.id"
            + where
            + " GROUP BY m.id,u.id,m.membership_started_at ORDER BY m.id";
    if (memberId != null) return jdbc.queryForList(sql + " LIMIT 1", memberId);
    return jdbc.queryForList(sql + " LIMIT ?", limit);
  }

  private static LocalDate localDate(Object value) {
    Instant instant;
    if (value instanceof Timestamp timestamp) instant = timestamp.toInstant();
    else if (value instanceof LocalDateTime local) instant = local.toInstant(ZoneOffset.UTC);
    else if (value instanceof Instant temporal) instant = temporal;
    else throw new IllegalStateException("Unsupported timestamp");
    return instant.atZone(BUSINESS_ZONE).toLocalDate();
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof Instant temporal) return temporal;
    throw new IllegalStateException("Unsupported timestamp");
  }

  private static String iso(Instant value) {
    return DateTimeFormatter.ISO_INSTANT.format(value);
  }
}
