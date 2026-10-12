package io.github.dream07code.innovationspace.dashboard;

import static io.github.dream07code.innovationspace.dashboard.DashboardQuerySupport.*;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardLeaderService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DashboardLeaderService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Map<String, Object> summary(SessionPrincipal actor, String rangeValue) {
    if (!"DIRECTION_LEADER".equals(actor.role())) throw new ApiException(403, ErrorCode.FORBIDDEN);
    DashboardRange range = DashboardRange.parse(rangeValue, DashboardRange.lastDays("7d", clock));
    var out = new LinkedHashMap<String, Object>();
    out.put("range", range.raw());
    out.put("overview", overview(actor, range));
    out.put("students", students(actor, range));
    out.put("trends", trends(actor, range));
    out.put("attention", attention(actor, range));
    out.put("rankingPreview", rankingPreview(actor, range));
    return out;
  }

  private Map<String, Object> overview(SessionPrincipal actor, DashboardRange range) {
    var where = scopeWhere(actor);
    long active = queryLong(
        "SELECT COUNT(*) FROM member m JOIN app_user u ON u.member_id=m.id WHERE u.role='STUDENT'"
            + " AND m.membership_status='ACTIVE'" + where.sql(),
        where.args());
    long total = queryLong(
        "SELECT COUNT(*) FROM member m JOIN app_user u ON u.member_id=m.id WHERE u.role='STUDENT'" + where.sql(),
        where.args());
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    long daily = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY'"
            + " AND r.status='SUBMITTED' AND r.first_submitted_at>=? AND r.first_submitted_at<?" + where.sql(),
        prepend(where.args(), start, end));
    long weekly = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='WEEKLY'"
            + " AND r.status='SUBMITTED' AND r.first_submitted_at>=? AND r.first_submitted_at<?" + where.sql(),
        prepend(where.args(), start, end));
    long today = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY'"
            + " AND r.status='SUBMITTED' AND DATE(DATE_ADD(r.first_submitted_at, INTERVAL 8 HOUR))=?" + where.sql(),
        prepend(where.args(), range.toInclusive()));
    long pendingAi = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND r.score_status IN ('QUEUED','PROCESSING','RETRYING')" + where.sql(),
        where.args());
    long failedAi = queryLong(
        "SELECT COUNT(*) FROM report_scoring_task t JOIN report r ON r.id=t.report_id"
            + " JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND t.status='FAILED'" + where.sql(),
        where.args());
    long pendingReview = queryLong(
        "SELECT COUNT(*) FROM report_review rv JOIN report r ON r.id=rv.report_id"
            + " JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE rv.status='PENDING' AND u.role='STUDENT' AND m.membership_status='ACTIVE'" + where.sql(),
        where.args());
    long pendingOj = queryLong(
        "SELECT COUNT(*) FROM assessment_submission s JOIN member m ON m.id=s.member_id"
            + " JOIN app_user u ON u.member_id=m.id WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND s.status IN ('RECEIVED','JUDGING')" + where.sql(),
        where.args());
    var out = new LinkedHashMap<String, Object>();
    out.put("activeStudents", active);
    out.put("totalStudents", total);
    out.put("todayDailySubmitted", today);
    out.put("dailyCompletionRate", rate(daily, active * range.days()));
    out.put("weeklyCompletionRate", rate(weekly, active * Math.max(1, (range.days() + 6) / 7)));
    out.put("pendingAi", pendingAi);
    out.put("failedAi", failedAi);
    out.put("pendingReview", pendingReview);
    out.put("pendingOj", pendingOj);
    out.put("pointsTotal", queryLong(
        "SELECT COALESCE(SUM(CASE WHEN p.delta>0 THEN p.delta ELSE 0 END),0) FROM point_ledger p"
            + " JOIN member m ON m.id=p.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND p.business_effective_at>=?"
            + " AND p.business_effective_at<?" + where.sql(),
        prepend(where.args(), businessStart(range), businessEndExclusive(range))));
    return out;
  }

  private List<Map<String, Object>> students(SessionPrincipal actor, DashboardRange range) {
    var where = scopeWhere(actor);
    var rows = jdbc.queryForList(
        "SELECT m.id,m.name,m.membership_status,"
            + " MAX(CASE WHEN r.report_type='DAILY' AND r.status='SUBMITTED' THEN r.first_submitted_at END) lastDailyAt,"
            + " MAX(CASE WHEN r.report_type='WEEKLY' AND r.status='SUBMITTED' THEN r.first_submitted_at END) lastWeeklyAt,"
            + " COALESCE(SUM(CASE WHEN p.delta>0 THEN p.delta ELSE 0 END),0) points"
            + " FROM member m JOIN app_user u ON u.member_id=m.id"
            + " LEFT JOIN report r ON r.member_id=m.id"
            + " LEFT JOIN point_ledger p ON p.member_id=m.id"
            + " WHERE u.role='STUDENT'" + where.sql()
            + " GROUP BY m.id,m.name,m.membership_status ORDER BY points DESC,m.id ASC",
        where.args());
    var sorted = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var item = new LinkedHashMap<String, Object>();
      item.put("memberId", id(row.get("id")));
      item.put("displayName", row.get("name"));
      item.put("membershipStatus", row.get("membership_status"));
      item.put("lastDailyAt", time(row.get("lastDailyAt")));
      item.put("lastWeeklyAt", time(row.get("lastWeeklyAt")));
      item.put("points", intValue(row.get("points")));
      item.put("directionRank", null);
      sorted.add(item);
    }
    for (int i = 0; i < sorted.size(); i++) {
      if (i > 0 && ((Number) sorted.get(i).get("points")).intValue()
          == ((Number) sorted.get(i - 1).get("points")).intValue())
        sorted.get(i).put("directionRank", sorted.get(i - 1).get("directionRank"));
      else sorted.get(i).put("directionRank", i + 1);
    }
    return sorted;
  }

  private List<Map<String, Object>> trends(SessionPrincipal actor, DashboardRange range) {
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    var where = scopeWhere(actor);
    var rows = jdbc.queryForList(
        "SELECT business_day,SUM(dailySubmitted) dailySubmitted,SUM(dailyScored) dailyScored,SUM(pointsAdded) pointsAdded FROM ("
            + " SELECT DATE(DATE_ADD(r.first_submitted_at, INTERVAL 8 HOUR)) business_day,COUNT(*) dailySubmitted,0 dailyScored,0 pointsAdded"
            + " FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY' AND r.status='SUBMITTED'"
            + " AND r.first_submitted_at>=? AND r.first_submitted_at<?" + where.sql() + " GROUP BY business_day"
            + " UNION ALL SELECT DATE(DATE_ADD(t.finished_at, INTERVAL 8 HOUR)) business_day,0,COUNT(*),0"
            + " FROM report_scoring_task t JOIN report r ON r.id=t.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND t.status='SCORED' AND t.finished_at>=? AND t.finished_at<?" + where.sql() + " GROUP BY 1"
            + " UNION ALL SELECT DATE(DATE_ADD(p.business_effective_at, INTERVAL 8 HOUR)) business_day,0,0,SUM(CASE WHEN p.delta>0 THEN p.delta ELSE 0 END)"
            + " FROM point_ledger p JOIN member m ON m.id=p.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND p.business_effective_at>=? AND p.business_effective_at<?" + where.sql()
            + " GROUP BY 1) grouped GROUP BY business_day",
        concat(
            new Object[] {start, end}, where.args(),
            new Object[] {start, end}, where.args(),
            new Object[] {start, end}, where.args()));
    var byDay = new LinkedHashMap<String, Map<String, Object>>();
    for (var row : rows) byDay.put(date(row.get("business_day")), row);
    var result = new ArrayList<Map<String, Object>>();
    for (LocalDate day = range.fromInclusive(); !day.isAfter(range.toInclusive()); day = day.plusDays(1)) {
      var source = byDay.get(day.toString());
      var item = new LinkedHashMap<String, Object>();
      item.put("date", day.toString());
      item.put("dailySubmitted", source == null ? 0 : intValue(source.get("dailySubmitted")));
      item.put("dailyScored", source == null ? 0 : intValue(source.get("dailyScored")));
      item.put("pointsAdded", source == null ? 0 : intValue(source.get("pointsAdded")));
      result.add(item);
    }
    return result;
  }

  private List<Map<String, Object>> attention(SessionPrincipal actor, DashboardRange range) {
    var where = scopeWhere(actor);
    var items = new ArrayList<Map<String, Object>>();
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,t.updated_at occurredAt,'FAILED' status"
            + " FROM report_scoring_task t JOIN report r ON r.id=t.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " WHERE t.status='FAILED' AND u.role='STUDENT' AND m.membership_status='ACTIVE'" + where.sql()
            + " ORDER BY t.updated_at DESC LIMIT 10", "SCORING_FAILED", where.args()));
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,rv.created_at occurredAt,'PENDING' status"
            + " FROM report_review rv JOIN report r ON r.id=rv.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " WHERE rv.status='PENDING' AND u.role='STUDENT' AND m.membership_status='ACTIVE'" + where.sql()
            + " ORDER BY rv.created_at DESC LIMIT 10", "PENDING_REVIEW", where.args()));
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,s.updated_at occurredAt,s.status status"
            + " FROM assessment_submission s JOIN member m ON m.id=s.member_id JOIN app_user u ON u.member_id=m.id"
            + " JOIN direction d ON d.id=m.direction_id WHERE s.status IN ('RECEIVED','JUDGING')"
            + " AND u.role='STUDENT' AND m.membership_status='ACTIVE'" + where.sql()
            + " ORDER BY s.updated_at DESC LIMIT 10", "PENDING_OJ", where.args()));
    items.sort(Comparator.comparingInt(DashboardLeaderService::priority));
    return items.size() <= 10 ? items : new ArrayList<>(items.subList(0, 10));
  }

  private List<Map<String, Object>> rankingPreview(SessionPrincipal actor, DashboardRange range) {
    return students(actor, range).stream()
        .limit(10)
        .map(student -> {
          var item = new LinkedHashMap<String, Object>();
          item.put("memberId", student.get("memberId"));
          item.put("displayName", student.get("displayName"));
          item.put("directionId", jdbc.queryForObject(
              "SELECT direction_id FROM member WHERE id=?", String.class, student.get("memberId")));
          item.put("directionName", jdbc.queryForObject(
              "SELECT name FROM direction WHERE id=(SELECT direction_id FROM member WHERE id=?)",
              String.class, student.get("memberId")));
          item.put("points", student.get("points"));
          item.put("rank", student.get("directionRank"));
          return (Map<String, Object>) item;
        })
        .toList();
  }

  private List<Map<String, Object>> attentionRows(String sql, String type, Object... args) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row : jdbc.queryForList(sql, args)) {
      var item = new LinkedHashMap<String, Object>();
      item.put("type", type);
      item.put("memberId", id(row.get("memberId")));
      item.put("displayName", row.get("displayName"));
      item.put("directionName", row.get("directionName"));
      item.put("occurredAt", time(row.get("occurredAt")));
      item.put("status", row.get("status"));
      result.add(item);
    }
    return result;
  }

  private static int priority(Map<String, Object> item) {
    return switch ((String) item.get("type")) {
      case "SCORING_FAILED" -> 1;
      case "PENDING_REVIEW" -> 2;
      case "PENDING_OJ" -> 3;
      default -> 4;
    };
  }

  private static Scope scopeWhere(SessionPrincipal actor) {
    if (actor.managedDirectionIds().isEmpty()) return new Scope(" AND 1=0", new Object[] {});
    return new Scope(
        " AND m.direction_id IN (" + String.join(",", java.util.Collections.nCopies(
            actor.managedDirectionIds().size(), "?")) + ")",
        actor.managedDirectionIds().toArray());
  }

  private static Object[] prepend(Object[] args, Object... extra) {
    return concat(extra, args);
  }

  private static Object[] concat(Object[]... groups) {
    int length = 0;
    for (var group : groups) length += group.length;
    var result = new Object[length];
    int offset = 0;
    for (var group : groups) {
      System.arraycopy(group, 0, result, offset, group.length);
      offset += group.length;
    }
    return result;
  }

  private long queryLong(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private record Scope(String sql, Object[] args) {}
}
