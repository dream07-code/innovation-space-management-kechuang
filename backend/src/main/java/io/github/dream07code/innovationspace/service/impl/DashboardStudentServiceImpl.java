package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.DashboardStudentService;
import io.github.dream07code.innovationspace.dashboard.*;


import static io.github.dream07code.innovationspace.dashboard.DashboardQuerySupport.*;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardStudentServiceImpl implements DashboardStudentService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DashboardStudentServiceImpl(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Map<String, Object> summary(SessionPrincipal actor, String rangeValue) {
    if (!"STUDENT".equals(actor.role()) || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    DashboardRange range = DashboardRange.parse(rangeValue, DashboardRange.lastDays("7d", clock));
    long memberId = actor.memberId();
    var out = new LinkedHashMap<String, Object>();
    out.put("range", range.raw());
    out.put("overview", overview(memberId, range));
    out.put("progress", progress(memberId, range));
    out.put("reports", reports(memberId));
    out.put("assessments", assessments(memberId));
    out.put("points", points(memberId));
    out.put("rankingPreview", ranking(memberId));
    return out;
  }

  private Map<String, Object> overview(long memberId, DashboardRange range) {
    var member = jdbc.queryForMap(
        "SELECT m.direction_id,m.membership_status FROM member m JOIN app_user u ON u.member_id=m.id"
            + " WHERE m.id=? AND u.role='STUDENT'",
        memberId);
    var ranks = ranking(memberId);
    var last = jdbc.queryForMap(
        "SELECT MAX(CASE WHEN report_type='DAILY' AND status='SUBMITTED' THEN first_submitted_at END) lastDaily,"
            + " MAX(CASE WHEN report_type='WEEKLY' AND status='SUBMITTED' THEN first_submitted_at END) lastWeekly"
            + " FROM report WHERE member_id=?",
        memberId);
    LocalDate today = range.toInclusive();
    Integer daysSince = daysSince(last.get("lastDaily"), today);
    var out = new LinkedHashMap<String, Object>();
    out.put("points", jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=?", Integer.class, memberId));
    out.put("overallRank", ranks.get("overallRank"));
    out.put("directionRank", ranks.get("directionRank"));
    out.put("daysSinceLastDaily", daysSince);
    out.put("dailyStatus", daysSince != null && daysSince == 0 ? "SUBMITTED" : "MISSING");
    out.put("weeklyStatus", daysSince(last.get("lastWeekly"), today) != null
        && daysSince(last.get("lastWeekly"), today) == 0 ? "SUBMITTED" : "MISSING");
    out.put("pendingOj", jdbc.queryForObject(
        "SELECT COUNT(*) FROM assessment_submission WHERE member_id=? AND status IN ('RECEIVED','JUDGING')",
        Integer.class, memberId));
    return out;
  }

  private Map<String, Object> progress(long memberId, DashboardRange range) {
    Timestamp start = businessStart(range);
    Timestamp end = businessEndExclusive(range);
    long daily = jdbc.queryForObject(
        "SELECT COUNT(*) FROM report WHERE member_id=? AND report_type='DAILY' AND status='SUBMITTED'"
            + " AND first_submitted_at>=? AND first_submitted_at<?", Long.class, memberId, start, end);
    long weekly = jdbc.queryForObject(
        "SELECT COUNT(*) FROM report WHERE member_id=? AND report_type='WEEKLY' AND status='SUBMITTED'"
            + " AND first_submitted_at>=? AND first_submitted_at<?", Long.class, memberId, start, end);
    long pointsAdded = jdbc.queryForObject(
        "SELECT COALESCE(SUM(CASE WHEN delta>0 THEN delta ELSE 0 END),0) FROM point_ledger"
            + " WHERE member_id=? AND business_effective_at>=? AND business_effective_at<?",
        Long.class, memberId, start, end);
    var last = jdbc.queryForMap(
        "SELECT MAX(first_submitted_at) lastDaily FROM report WHERE member_id=? AND report_type='DAILY'"
            + " AND status='SUBMITTED'", memberId);
    var out = new LinkedHashMap<String, Object>();
    out.put("dailySubmitted", daily);
    out.put("dailyTarget", range.days());
    out.put("weeklySubmitted", weekly);
    out.put("weeklyTarget", Math.max(1, (range.days() + 6) / 7));
    out.put("pointsAdded", pointsAdded);
    out.put("daysSinceLastDaily", daysSince(last.get("lastDaily"), range.toInclusive()));
    return out;
  }

  private List<Map<String, Object>> reports(long memberId) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row : jdbc.queryForList(
        "SELECT id,report_type,status,score_status,first_submitted_at FROM report WHERE member_id=?"
            + " ORDER BY COALESCE(first_submitted_at,created_at) DESC,id DESC LIMIT 10", memberId)) {
      var item = new LinkedHashMap<String, Object>();
      item.put("reportId", id(row.get("id")));
      item.put("reportType", row.get("report_type"));
      item.put("status", row.get("status"));
      item.put("scoreStatus", row.get("score_status"));
      item.put("submittedAt", time(row.get("first_submitted_at")));
      item.put("editable", "DRAFT".equals(row.get("status")));
      result.add(item);
    }
    return result;
  }

  private List<Map<String, Object>> assessments(long memberId) {
    var result = new ArrayList<Map<String, Object>>();
    Long directionId = jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?", Long.class, memberId);
    // 一场考核可以有多道程序题、也就有多条提交记录；直接 JOIN 会按提交条数把
    // 同一场考核重复列出来。先按考核取「本人最后一次提交」，一次考核只留一行。
    for (var row : jdbc.queryForList(
        "SELECT a.id,a.title,a.status,a.results_published_at,"
            + " l.status AS submissionStatus,r.technical_verdict,r.final_score,r.score_source"
            + " FROM assessment a"
            + " LEFT JOIN ("
            + "   SELECT s.assessment_id,s.status,s.id,"
            + "     ROW_NUMBER() OVER (PARTITION BY s.assessment_id ORDER BY s.submitted_at DESC,s.id DESC) AS rn"
            + "   FROM assessment_submission s WHERE s.member_id=?"
            + " ) l ON l.assessment_id=a.id AND l.rn=1"
            + " LEFT JOIN assessment_result r ON r.submission_id=l.id"
            + " WHERE a.direction_id=? AND a.status IN ('PUBLISHED','CLOSED','ARCHIVED')"
            + " ORDER BY a.end_at DESC,a.id DESC LIMIT 10", memberId, directionId)) {
      // 与成绩页同一口径：未发布时不下发分数，避免首页提前暴露成绩。
      boolean published = row.get("results_published_at") != null;
      var item = new LinkedHashMap<String, Object>();
      item.put("assessmentId", id(row.get("id")));
      item.put("title", row.get("title"));
      item.put("status", row.get("status"));
      item.put("resultsPublished", published);
      item.put("submissionStatus", row.get("submissionStatus") == null ? "NONE" : row.get("submissionStatus"));
      item.put("technicalVerdict", row.get("technical_verdict"));
      item.put("finalScore", published ? row.get("final_score") : null);
      item.put("scoreSource", row.get("score_source") == null ? "PENDING" : row.get("score_source"));
      result.add(item);
    }
    return result;
  }

  private List<Map<String, Object>> points(long memberId) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row : jdbc.queryForList(
        "SELECT id,delta,source,business_effective_at FROM point_ledger WHERE member_id=?"
            + " ORDER BY business_effective_at DESC,id DESC LIMIT 10", memberId)) {
      var item = new LinkedHashMap<String, Object>();
      item.put("entryId", id(row.get("id")));
      item.put("delta", row.get("delta"));
      item.put("source", row.get("source"));
      item.put("reason", null);
      item.put("effectiveAt", time(row.get("business_effective_at")));
      result.add(item);
    }
    return result;
  }

  private Map<String, Object> ranking(long memberId) {
    var rows = jdbc.queryForList(
        "SELECT m.id,m.direction_id,COALESCE(SUM(p.delta),0) points FROM member m"
            + " JOIN app_user u ON u.member_id=m.id LEFT JOIN point_ledger p ON p.member_id=m.id"
            + " WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'"
            + " GROUP BY m.id,m.direction_id ORDER BY points DESC,m.id ASC");
    var ranked = new ArrayList<Map<String, Object>>();
    for (var row : rows) ranked.add(row);
    var currentDirection = jdbc.queryForObject("SELECT direction_id FROM member WHERE id=?", Long.class, memberId);
    var out = new LinkedHashMap<String, Object>();
    out.put("overallRank", null);
    out.put("overallCount", ranked.size());
    out.put("directionRank", null);
    out.put("directionCount", (int) ranked.stream().filter(row ->
        ((Number) row.get("direction_id")).longValue() == currentDirection).count());
    out.put("eligible", jdbc.queryForObject(
        "SELECT membership_status='ACTIVE' FROM member WHERE id=?", Boolean.class, memberId));
    int previousRank = 0;
    long previousPoints = Long.MIN_VALUE;
    for (int i = 0; i < ranked.size(); i++) {
      long points = ((Number) ranked.get(i).get("points")).longValue();
      int rank = points == previousPoints ? previousRank : i + 1;
      previousPoints = points;
      previousRank = rank;
      if (((Number) ranked.get(i).get("id")).longValue() != memberId) continue;
      out.put("overallRank", rank);
      var directionRanked = ranked.stream()
          .filter(row -> ((Number) row.get("direction_id")).longValue() == currentDirection)
          .sorted(Comparator.comparing((Map<String, Object> row) -> ((Number) row.get("points")).longValue()).reversed()
              .thenComparing(row -> ((Number) row.get("id")).longValue()))
          .toList();
      int previousDirectionRank = 0;
      long previousDirectionPoints = Long.MIN_VALUE;
      Integer directionRank = null;
      for (int j = 0; j < directionRanked.size(); j++) {
        long directionPoints = ((Number) directionRanked.get(j).get("points")).longValue();
        int directionRankValue = directionPoints == previousDirectionPoints ? previousDirectionRank : j + 1;
        previousDirectionPoints = directionPoints;
        previousDirectionRank = directionRankValue;
        if (((Number) directionRanked.get(j).get("id")).longValue() == memberId) {
          directionRank = directionRankValue;
          break;
        }
      }
      out.put("directionRank", directionRank);
      break;
    }
    return out;
  }

  private static Integer daysSince(Object value, LocalDate today) {
    if (value == null) return null;
    LocalDate date = value instanceof Timestamp timestamp
        ? timestamp.toInstant().atZone(BUSINESS_ZONE).toLocalDate()
        : LocalDate.parse(String.valueOf(value).substring(0, 10));
    return (int) ChronoUnit.DAYS.between(date, today);
  }
}
