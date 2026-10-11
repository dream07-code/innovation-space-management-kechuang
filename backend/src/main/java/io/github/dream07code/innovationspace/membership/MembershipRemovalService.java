package io.github.dream07code.innovationspace.membership;

import io.github.dream07code.innovationspace.points.PointLedgerService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MembershipRemovalService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final PointLedgerService points;
  private final InactivityPolicy policy;
  private final Clock clock;
  private final MembershipRemovalProperties properties;
  private final TransactionTemplate tx;

  public MembershipRemovalService(
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
      if (removeDueMember(((Number) candidate.get("id")).longValue())) removed++;
    }
    return removed;
  }

  public boolean removeDueMember(long memberId) {
    var candidate = candidates(memberId, 1).stream().findFirst().orElse(null);
    if (candidate == null) return false;
    LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
    var evaluation =
        policy.evaluate(
            businessDate,
            localDate(candidate.get("membership_started_at")),
            candidate.get("last_daily_submitted_at") == null
                ? null
                : localDate(candidate.get("last_daily_submitted_at")));
    if (!evaluation.removable()) return false;
    Instant now = clock.instant();
    Boolean removed =
        tx.execute(
            status -> {
              var locked =
                  jdbc.queryForMap(
                      "SELECT m.id,m.membership_started_at,u.id user_id FROM member m"
                          + " JOIN app_user u ON u.member_id=m.id WHERE m.id=? AND m.membership_status='ACTIVE'"
                          + " AND u.role='STUDENT' FOR UPDATE",
                      memberId);
              int changed =
                  jdbc.update(
                      "UPDATE member SET membership_status='REMOVED',removed_at=?,removal_reason=?,"
                          + "version=version+1,updated_at=? WHERE id=? AND membership_status='ACTIVE'",
                      Timestamp.from(now),
                      "TWO_WEEKS_NO_DAILY_REPORT",
                      Timestamp.from(now),
                      memberId);
              if (changed == 0) return false;
              long userId = ((Number) locked.get("user_id")).longValue();
              points.reverseNonZeroEntries(
                  memberId, InactivityPolicy.RULE_VERSION, now);
              jdbc.update(
                  "UPDATE app_user SET auth_revision=auth_revision+1 WHERE id=?", userId);
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
                  timestampText(candidate.get("membership_started_at")),
                  candidate.get("last_daily_submitted_at") == null
                      ? null
                      : timestampText(candidate.get("last_daily_submitted_at")),
                  evaluation.completedInactiveDays(),
                  InactivityPolicy.THRESHOLD_DAYS,
                  InactivityPolicy.RULE_VERSION,
                  Timestamp.from(now));
              return true;
            });
    return Boolean.TRUE.equals(removed);
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

  private static String timestampText(Object value) {
    return value == null ? null : value.toString();
  }
}
