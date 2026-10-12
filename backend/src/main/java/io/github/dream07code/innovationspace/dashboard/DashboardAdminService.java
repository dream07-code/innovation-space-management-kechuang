package io.github.dream07code.innovationspace.dashboard;

import static io.github.dream07code.innovationspace.dashboard.DashboardQuerySupport.*;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.time.Clock;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardAdminService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DashboardAdminService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Map<String, Object> summary(SessionPrincipal actor, String rangeValue) {
    if (!"COLLEGE_ADMIN".equals(actor.role())) throw new ApiException(403, ErrorCode.FORBIDDEN);
    DashboardRange range = DashboardRange.parse(
        rangeValue, DashboardRange.lastDays("30d", clock));
    var out = new LinkedHashMap<String, Object>();
    out.put("range", range.raw());
    out.put("overview", overview(range));
    out.put("trends", trends(range));
    out.put("directions", directions(range));
    out.put("attention", attention(range));
    out.put("recentActivity", recentActivity());
    return out;
  }

  private Map<String, Object> overview(DashboardRange range) {
    var counts = jdbc.queryForMap(
        "SELECT"
            + " SUM(CASE WHEN u.role='STUDENT' THEN 1 ELSE 0 END) totalStudents,"
            + " SUM(CASE WHEN u.role='STUDENT' AND m.membership_status='ACTIVE' THEN 1 ELSE 0 END) activeStudents,"
            + " SUM(CASE WHEN u.role='DIRECTION_LEADER' THEN 1 ELSE 0 END) directionLeaders,"
            + " SUM(CASE WHEN u.role='STUDENT' AND m.membership_status='REMOVED' THEN 1 ELSE 0 END) removedStudents"
            + " FROM member m JOIN app_user u ON u.member_id=m.id");
    long active = longValue(counts.get("activeStudents"));
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    long daily = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY'"
            + " AND r.status='SUBMITTED' AND r.first_submitted_at>=? AND r.first_submitted_at<?",
        start, end);
    long weekly = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='WEEKLY'"
            + " AND r.status='SUBMITTED' AND r.first_submitted_at>=? AND r.first_submitted_at<?",
        start, end);
    long today = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY'"
            + " AND r.status='SUBMITTED' AND DATE(DATE_ADD(r.first_submitted_at, INTERVAL 8 HOUR))=?",
        range.toInclusive());
    long pendingAi = queryLong(
        "SELECT COUNT(*) FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND r.score_status IN ('QUEUED','PROCESSING','RETRYING')");
    long failedAi = queryLong(
        "SELECT COUNT(*) FROM report_scoring_task t"
            + " JOIN report r ON r.id=t.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND t.status='FAILED'");
    long pendingReview = queryLong(
        "SELECT (SELECT COUNT(*) FROM report_review rv JOIN report r ON r.id=rv.report_id"
            + " JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE rv.status='PENDING' AND u.role='STUDENT' AND m.membership_status='ACTIVE')"
            + " + (SELECT COUNT(*) FROM assessment_result ar JOIN assessment_submission s ON s.id=ar.submission_id"
            + " JOIN member m ON m.id=s.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE ar.result_status='NEEDS_REVIEW' AND u.role='STUDENT' AND m.membership_status='ACTIVE')");
    long pendingOj = queryLong(
        "SELECT COUNT(*) FROM assessment_submission s JOIN member m ON m.id=s.member_id"
            + " JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND s.status IN ('RECEIVED','JUDGING')");
    long weekBuckets = Math.max(1, (range.days() + 6) / 7);
    var out = new LinkedHashMap<String, Object>();
    out.put("activeStudents", active);
    out.put("totalStudents", longValue(counts.get("totalStudents")));
    out.put("directionLeaders", longValue(counts.get("directionLeaders")));
    out.put("removedStudents", longValue(counts.get("removedStudents")));
    out.put("todayDailySubmitted", today);
    out.put("dailyCompletionRate", rate(daily, active * range.days()));
    out.put("weeklyCompletionRate", rate(weekly, active * weekBuckets));
    out.put("pendingAi", pendingAi);
    out.put("failedAi", failedAi);
    out.put("pendingReview", pendingReview);
    out.put("pendingOj", pendingOj);
    return out;
  }

  private List<Map<String, Object>> trends(DashboardRange range) {
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    var rows = jdbc.queryForList(
        "SELECT business_day,SUM(dailySubmitted) dailySubmitted,SUM(dailyScored) dailyScored,SUM(pointsAdded) pointsAdded FROM ("
            + " SELECT DATE(DATE_ADD(r.first_submitted_at, INTERVAL 8 HOUR)) business_day,COUNT(*) dailySubmitted,0 dailyScored,0 pointsAdded"
            + " FROM report r JOIN member m ON m.id=r.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND r.report_type='DAILY' AND r.status='SUBMITTED'"
            + " AND r.first_submitted_at>=? AND r.first_submitted_at<? GROUP BY business_day"
            + " UNION ALL SELECT DATE(DATE_ADD(t.finished_at, INTERVAL 8 HOUR)),0,COUNT(*),0"
            + " FROM report_scoring_task t JOIN report r ON r.id=t.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " AND t.status='SCORED' AND t.finished_at>=? AND t.finished_at<? GROUP BY 1"
            + " UNION ALL SELECT DATE(DATE_ADD(p.business_effective_at, INTERVAL 8 HOUR)),0,0,SUM(CASE WHEN p.delta>0 THEN p.delta ELSE 0 END)"
            + " FROM point_ledger p JOIN member m ON m.id=p.member_id JOIN app_user u ON u.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE' AND p.business_effective_at>=? AND p.business_effective_at<?"
            + " GROUP BY 1) grouped GROUP BY business_day",
        start, end, start, end, start, end);
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

  private List<Map<String, Object>> directions(DashboardRange range) {
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    var rows = jdbc.queryForList(
        "SELECT d.id,d.name,COUNT(DISTINCT CASE WHEN u.role='STUDENT' AND m.membership_status='ACTIVE' THEN m.id END) activeStudents,"
            + " COUNT(DISTINCT CASE WHEN r.report_type='DAILY' AND r.status='SUBMITTED' THEN r.id END) dailySubmitted,"
            + " COUNT(DISTINCT CASE WHEN r.report_type='WEEKLY' AND r.status='SUBMITTED' THEN r.id END) weeklySubmitted,"
            + " COALESCE((SELECT SUM(CASE WHEN lp.delta>0 THEN lp.delta ELSE 0 END) FROM point_ledger lp JOIN member lm ON lm.id=lp.member_id JOIN app_user lu ON lu.member_id=lm.id WHERE lm.direction_id=d.id AND lu.role='STUDENT' AND lm.membership_status='ACTIVE'),0) pointsTotal,"
            + " COUNT(DISTINCT CASE WHEN t.status='FAILED' THEN t.id END) failedAi,"
            + " COUNT(DISTINCT CASE WHEN rv.status='PENDING' THEN rv.id END) pendingReview,"
            + " COUNT(DISTINCT CASE WHEN s.status IN ('RECEIVED','JUDGING') THEN s.id END) pendingOj"
            + " FROM direction d"
            + " LEFT JOIN member m ON m.direction_id=d.id"
            + " LEFT JOIN app_user u ON u.member_id=m.id"
            + " LEFT JOIN report r ON r.member_id=m.id AND r.first_submitted_at>=? AND r.first_submitted_at<?"
            + " LEFT JOIN report_scoring_task t ON t.report_id=r.id AND t.status='FAILED'"
            + " LEFT JOIN report_review rv ON rv.report_id=r.id AND rv.status='PENDING'"
            + " LEFT JOIN assessment_submission s ON s.member_id=m.id AND s.status IN ('RECEIVED','JUDGING')"
            + " GROUP BY d.id,d.name ORDER BY activeStudents DESC,d.id ASC",
        start, end);
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      long active = longValue(row.get("activeStudents"));
      int pending = intValue(row.get("failedAi")) + intValue(row.get("pendingReview")) + intValue(row.get("pendingOj"));
      var item = new LinkedHashMap<String, Object>();
      item.put("directionId", id(row.get("id")));
      item.put("directionName", row.get("name"));
      item.put("activeStudents", active);
      item.put("dailyCompletionRate", rate(longValue(row.get("dailySubmitted")), active * range.days()));
      item.put("weeklyCompletionRate",
          rate(longValue(row.get("weeklySubmitted")), active * Math.max(1, (range.days() + 6) / 7)));
      item.put("averagePoints", active == 0 ? 0D : Math.round(longValue(row.get("pointsTotal")) * 100D / active) / 100D);
      item.put("pendingItems", pending);
      result.add(item);
    }
    return result;
  }

  private List<Map<String, Object>> attention(DashboardRange range) {
    var items = new ArrayList<Map<String, Object>>();
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,t.updated_at occurredAt,'FAILED' status"
            + " FROM report_scoring_task t JOIN report r ON r.id=t.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " WHERE t.status='FAILED' AND u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " ORDER BY t.updated_at DESC LIMIT 10",
        "SCORING_FAILED"));
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,rv.created_at occurredAt,'PENDING' status"
            + " FROM report_review rv JOIN report r ON r.id=rv.report_id JOIN member m ON m.id=r.member_id"
            + " JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " WHERE rv.status='PENDING' AND u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " ORDER BY rv.created_at DESC LIMIT 10",
        "PENDING_REVIEW"));
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,s.updated_at occurredAt,s.status status"
            + " FROM assessment_submission s JOIN member m ON m.id=s.member_id"
            + " JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " WHERE s.status IN ('RECEIVED','JUDGING') AND u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " ORDER BY s.updated_at DESC LIMIT 10",
        "PENDING_OJ"));
    items.addAll(attentionRows(
        "SELECT m.id memberId,m.name displayName,d.name directionName,"
            + " COALESCE(MAX(r.first_submitted_at),m.membership_started_at) occurredAt,'AT_RISK' status"
            + " FROM member m JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id"
            + " LEFT JOIN report r ON r.member_id=m.id AND r.report_type='DAILY' AND r.status='SUBMITTED'"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " GROUP BY m.id,m.name,d.name,m.membership_started_at"
            + " HAVING DATE(DATE_ADD(COALESCE(MAX(r.first_submitted_at),m.membership_started_at), INTERVAL 8 HOUR))"
            + " <= DATE_SUB(?, INTERVAL 13 DAY)"
            + " ORDER BY occurredAt ASC LIMIT 10",
        "INACTIVITY_RISK", range.toInclusive()));
    items.sort((left, right) -> Integer.compare(priority(left), priority(right)));
    return items.size() <= 10 ? items : new ArrayList<>(items.subList(0, 10));
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

  private List<Map<String, Object>> recentActivity() {
    var items = new ArrayList<Map<String, Object>>();
    for (var row : jdbc.queryForList(
        "SELECT action,summary,occurred_at FROM audit_log ORDER BY occurred_at DESC,id DESC LIMIT 10")) {
      items.add(activity("AUDIT", row.get("occurred_at"), row.get("summary")));
    }
    for (var row : jdbc.queryForList(
        "SELECT action,summary,occurred_at FROM assessment_audit ORDER BY occurred_at DESC,id DESC LIMIT 10")) {
      items.add(activity("ASSESSMENT_AUDIT", row.get("occurred_at"), row.get("summary")));
    }
    items.sort((left, right) -> ((String) right.get("occurredAt")).compareTo((String) left.get("occurredAt")));
    return items.size() <= 10 ? items : new ArrayList<>(items.subList(0, 10));
  }

  private Map<String, Object> activity(String type, Object occurredAt, Object summary) {
    var item = new LinkedHashMap<String, Object>();
    item.put("type", type);
    item.put("occurredAt", time(occurredAt));
    item.put("summary", summary);
    return item;
  }

  private static int priority(Map<String, Object> item) {
    return switch ((String) item.get("type")) {
      case "SCORING_FAILED" -> 1;
      case "PENDING_REVIEW" -> 2;
      case "PENDING_OJ" -> 3;
      default -> 4;
    };
  }

  private long queryLong(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }
}
