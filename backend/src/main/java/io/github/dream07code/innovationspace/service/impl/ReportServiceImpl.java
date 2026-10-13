package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.ReportService;
import io.github.dream07code.innovationspace.report.*;
import io.github.dream07code.innovationspace.points.*;


import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.service.PointLedgerService;
import io.github.dream07code.innovationspace.points.PointsPolicyProperties;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportServiceImpl implements ReportService {
  private static final DateTimeFormatter OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final ReportProperties props;
  private final PointsPolicyProperties pointsPolicy;
  private final PointLedgerService points;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ReportPeriodPolicy periods;
  private final JsonMapper json = JsonMapper.builder().build();

  public ReportServiceImpl(
      JdbcTemplate jdbc,
      IdempotencyService idem,
      ReportProperties props,
      PointsPolicyProperties pointsPolicy,
      PointLedgerService points,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.props = props;
    this.pointsPolicy = pointsPolicy;
    this.points = points;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.periods = new ReportPeriodPolicy(clock);
  }

  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal principal, String key, ReportType type) {
    if (principal.memberId() == null) throw new ApiException(403, ErrorCode.FORBIDDEN);
    ReportScopePolicy.requireOwner(principal, principal.memberId());
    Map<String, Object> content = normalizeContent(request, type, true);
    if (type == ReportType.DAILY
        && (!(content.get("learningContent") instanceof String learning)
            || learning.isBlank())) throw bad();
    LocalDate day = periods.dailyPeriod();
    ReportPeriodPolicy.WeekPeriod week = periods.weeklyPeriod();
    String reportDate = type == ReportType.DAILY ? day.toString() : null;
    String weekStart = type == ReportType.WEEKLY ? week.start().toString() : null;
    String weekEnd = type == ReportType.WEEKLY ? week.end().toString() : null;
    if (type == ReportType.DAILY
        && request.get("reportDate") != null
        && (!day.toString().equals(request.get("reportDate")))) throw bad();
    if (type == ReportType.WEEKLY
        && (!week.start().toString().equals(request.get("weekStart")))) throw bad();
    String path = "/api/v1/" + (type == ReportType.DAILY ? "daily-reports" : "weekly-reports");
    return idem.execute(
        principal,
        "POST",
        path,
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  String duplicate =
                      type == ReportType.DAILY
                          ? "SELECT id FROM report WHERE member_id=? AND report_type='DAILY' AND report_date=?"
                          : "SELECT id FROM report WHERE member_id=? AND report_type='WEEKLY' AND week_start=?";
                  if (!jdbc
                      .queryForList(
                          duplicate, principal.memberId(), type == ReportType.DAILY ? reportDate : weekStart)
                      .isEmpty()) throw new ApiException(409, ErrorCode.REPORT_ALREADY_EXISTS);
                  long direction =
                      jdbc.queryForObject(
                          "SELECT direction_id FROM member WHERE id=? AND membership_status='ACTIVE'",
                          Long.class,
                          principal.memberId());
                  Instant now = clock.instant();
                  jdbc.update(
                      "INSERT INTO report(member_id,direction_id,report_type,report_date,week_start,week_end,status,current_version,score_status,created_at,updated_at) VALUES (?,?,?,?,?,?, 'DRAFT',1,'NOT_REQUESTED',?,?)",
                      principal.memberId(),
                      direction,
                      type.name(),
                      reportDate,
                      weekStart,
                      weekEnd,
                      Timestamp.from(now),
                      Timestamp.from(now));
                  long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                  insertVersion(id, 1, principal.userId(), content, now);
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  public Map<String, Object> get(long id, SessionPrincipal principal) {
    Map<String, Object> row = locked(id, false);
    requireVisible(principal, row);
    return response(id);
  }

  public Map<String, Object> list(
      ReportType type,
      SessionPrincipal principal,
      int page,
      int size,
      Long memberId,
      Long directionId,
      String fromDate,
      String toDate,
      String status,
      String scoreStatus) {
    validatePage(page, size);
    LocalDate from = parseDate(fromDate);
    LocalDate to = parseDate(toDate);
    if (from != null && to != null && from.isAfter(to)) throw bad();
    if (status != null && !status.equals("DRAFT") && !status.equals("SUBMITTED")) throw bad();
    if (scoreStatus != null && !validScoreStatus(scoreStatus)) throw bad();
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE r.report_type=?");
    args.add(type.name());
    if ("COLLEGE_ADMIN".equals(principal.role())) {
      if ("DRAFT".equals(status)) throw new ApiException(403, ErrorCode.FORBIDDEN);
      where.append(" AND r.status='SUBMITTED'");
    } else if ("DIRECTION_LEADER".equals(principal.role())) {
      if (principal.managedDirectionIds().isEmpty()) {
        where.append(" AND r.member_id=?");
        args.add(principal.memberId());
      } else {
        String placeholders = String.join(",", java.util.Collections.nCopies(principal.managedDirectionIds().size(), "?"));
        where.append(" AND (r.member_id=? OR (r.status='SUBMITTED' AND r.direction_id IN (")
            .append(placeholders)
            .append(")))");
        args.add(principal.memberId());
        args.addAll(principal.managedDirectionIds());
      }
    } else {
      where.append(" AND r.member_id=?");
      args.add(principal.memberId());
    }
    if (memberId != null) {
      requireRequestedMember(principal, memberId);
      where.append(" AND r.member_id=?");
      args.add(memberId);
    }
    if (directionId != null) {
      requireRequestedDirection(principal, directionId);
      where.append(" AND r.direction_id=?");
      args.add(directionId);
    }
    if (from != null) {
      where.append(" AND COALESCE(r.report_date,r.week_start)>=?");
      args.add(from);
    }
    if (to != null) {
      where.append(" AND COALESCE(r.report_date,r.week_start)<=?");
      args.add(to);
    }
    if (status != null) {
      where.append(" AND r.status=?");
      args.add(status);
    }
    if (scoreStatus != null) {
      where.append(" AND r.score_status=?");
      args.add(scoreStatus);
    }
    String fromSql = " FROM report r" + where;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT r.id"
                + fromSql
                + " ORDER BY COALESCE(r.report_date,r.week_start) DESC,r.id DESC LIMIT ? OFFSET ?",
            Long.class,
            append(args, size, (page - 1) * size));
    List<Map<String, Object>> items = new ArrayList<>();
    for (Long id : ids) items.add(response(id));
    Long total = jdbc.queryForObject("SELECT COUNT(*)" + fromSql, Long.class, args.toArray());
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  public IdempotencyService.StoredResponse submit(
      long id, long version, SessionPrincipal principal, String key) {
    return idem.execute(
        principal,
        "POST",
        "/api/v1/" + reportPath(id) + "/" + id + "/submit",
        key,
        json.writeValueAsString(Map.of("version", version)),
        () ->
            tx.execute(
                status -> {
          lockMemberForReport(id);
          Map<String, Object> row = locked(id, true);
          ReportScopePolicy.requireOwner(principal, ((Number) row.get("member_id")).longValue());
          if (((Number) row.get("current_version")).longValue() != version)
            throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
          if ("SUBMITTED".equals(row.get("status")))
            return new IdempotencyService.StoredResponse(200, json.writeValueAsString(ApiResponse.success(response(id))));
          requireCurrentPeriod(id, row);
          ReportType type = ReportType.valueOf((String) row.get("report_type"));
          Map<String, Object> content = content(id, version);
          if (!ReportScopePolicy.validContent(type, content, true)) throw bad();
          requireReadyRules();
          long versionId =
              jdbc.queryForObject(
                  "SELECT id FROM report_version WHERE report_id=? AND version=?", Long.class, id, version);
          Instant now = clock.instant();
          Instant editableUntil = periodEnd(type, row).atZone(BUSINESS_ZONE).toInstant();
          jdbc.update(
              "UPDATE report SET status='SUBMITTED',first_submitted_at=?,editable_until=?,score_status='QUEUED',rubric_version='v1',updated_at=? WHERE id=?",
              Timestamp.from(now),
              Timestamp.from(editableUntil),
              Timestamp.from(now),
              id);
          int base = type == ReportType.DAILY ? props.getDailyBasePoints() : props.getWeeklyBasePoints();
          points.appendIfAbsent(
              ((Number) row.get("member_id")).longValue(),
              id,
              versionId,
              base,
              "SUBMISSION_BASE",
              "REPORT_SUBMISSION:" + id + ":" + version,
              now);
          insertTask(id, versionId, now);
          return new IdempotencyService.StoredResponse(200, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  public Map<String, Object> update(
      long id, Map<String, Object> request, SessionPrincipal principal, ReportType type) {
    return tx.execute(
        status -> {
          lockMemberForReport(id);
          Map<String, Object> row = locked(id, true);
          ReportScopePolicy.requireOwner(principal, ((Number) row.get("member_id")).longValue());
          ReportType actual = ReportType.valueOf((String) row.get("report_type"));
          if (actual != type) throw new ApiException(404, ErrorCode.NOT_FOUND);
          Object rawVersion = request.get("version");
          if (!(rawVersion instanceof Number number) || number.longValue() < 1) throw bad();
          if (request.size() < 2) throw bad();
          long version = number.longValue();
          if (((Number) row.get("current_version")).longValue() != version)
            throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
          ReportStatus reportStatus = ReportStatus.valueOf((String) row.get("status"));
          if ("MANUAL".equals(row.get("score_status"))) throw new ApiException(409, ErrorCode.INVALID_STATE);
          if (reportStatus == ReportStatus.DRAFT) requireCurrentPeriod(id, row);
          Instant editableUntil =
              row.get("editable_until") == null ? null : instant(row.get("editable_until"));
          if (!periods.isEditable(reportStatus, editableUntil, clock.instant()))
            throw new ApiException(409, ErrorCode.INVALID_STATE);
          Map<String, Object> patch = new LinkedHashMap<>(request);
          patch.remove("version");
          if (patch.containsKey("reportDate") || patch.containsKey("weekStart") || patch.containsKey("weekEnd")) throw bad();
          Map<String, Object> merged = new LinkedHashMap<>(content(id, version));
          merged.putAll(normalizeContent(patch, type, false));
          normalizeMerged(merged, type);
          if (reportStatus == ReportStatus.SUBMITTED
              && !ReportScopePolicy.validContent(type, merged, true)) throw bad();
          long newVersion = version + 1;
          Instant now = clock.instant();
          jdbc.update(
              "UPDATE report_version SET is_current=FALSE WHERE report_id=? AND version=? AND is_current=TRUE",
              id,
              version);
          insertVersion(id, newVersion, principal.userId(), merged, now);
          if (reportStatus == ReportStatus.SUBMITTED) {
            jdbc.update(
                "UPDATE report SET current_version=?,score_status='QUEUED',final_score=NULL,score_reason=NULL,updated_at=? WHERE id=?",
                newVersion,
                Timestamp.from(now),
                id);
            long versionId =
                jdbc.queryForObject(
                    "SELECT id FROM report_version WHERE report_id=? AND version=?",
                    Long.class,
                    id,
                    newVersion);
            insertTask(id, versionId, now);
          } else {
            jdbc.update(
                "UPDATE report SET current_version=?,updated_at=? WHERE id=?",
                newVersion,
                Timestamp.from(now),
                id);
          }
          return response(id);
        });
  }

  public Map<String, Object> response(long id) {
    Map<String, Object> row = locked(id, false);
    long version = ((Number) row.get("current_version")).longValue();
    ReportType type = ReportType.valueOf((String) row.get("report_type"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("directionId", String.valueOf(row.get("direction_id")));
    out.put("status", row.get("status"));
    out.put("version", version);
    out.put("scoreStatus", row.get("score_status"));
    int awarded =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?", Integer.class, id);
    out.put("awardedPoints", awarded);
    out.put("finalScore", row.get("final_score"));
    out.put("scoreReason", row.get("score_reason"));
    out.put("firstSubmittedAt", timestamp(row.get("first_submitted_at")));
    out.put("updatedAt", timestamp(row.get("updated_at")));
    Instant editableUntil =
        row.get("editable_until") == null
            ? periodEnd(type, row).atZone(BUSINESS_ZONE).toInstant()
            : instant(row.get("editable_until"));
    out.put(
        "editable",
        periods.isEditable(
            ReportStatus.valueOf((String) row.get("status")), editableUntil, clock.instant()));
    out.put("editableUntil", OFFSET.format(editableUntil.atZone(BUSINESS_ZONE)));
    out.put("ruleVersion", row.get("rubric_version"));
    if (type == ReportType.DAILY) out.put("reportDate", date(row.get("report_date")));
    else {
      out.put("weekStart", date(row.get("week_start")));
      out.put("weekEnd", date(row.get("week_end")));
    }
    out.putAll(content(id, version));
    return out;
  }

  private Map<String, Object> content(long id, long version) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT content_json FROM report_version WHERE report_id=? AND version=?", id, version);
    if (rows.isEmpty()) return Map.of();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> value =
          json.readValue((String) rows.getFirst().get("content_json"), Map.class);
      return value;
    } catch (Exception error) {
      throw new IllegalStateException("Stored report content is invalid");
    }
  }

  private void insertVersion(
      long id, long version, long user, Map<String, Object> content, Instant now) {
    String text = json.writeValueAsString(content);
    jdbc.update(
        "INSERT INTO report_version(report_id,version,content_json,content_hash,edited_by,created_at,is_current) VALUES(?,?,?,UNHEX(SHA2(?,256)),?,?,TRUE)",
        id,
        version,
        text,
        text,
        user,
        Timestamp.from(now));
  }

  private void insertTask(long reportId, long versionId, Instant now) {
    jdbc.update(
        "INSERT IGNORE INTO report_scoring_task(report_id,report_version_id,status,attempt,rubric_version,created_at,updated_at) VALUES(?,?, 'QUEUED',0,'v1',?,?)",
        reportId,
        versionId,
        Timestamp.from(now),
        Timestamp.from(now));
  }


  private void lockMemberForReport(long reportId) {
    Long memberId = jdbc.queryForObject("SELECT member_id FROM report WHERE id=?", Long.class, reportId);
    Integer active = jdbc.queryForObject(
        "SELECT COUNT(*) FROM member WHERE id=? AND membership_status='ACTIVE' FOR UPDATE",
        Integer.class, memberId);
    if (active == null || active != 1) throw new ApiException(409, ErrorCode.INVALID_STATE);
  }

  private Map<String, Object> locked(long id, boolean lock) {
    String query = "SELECT * FROM report WHERE id=?" + (lock ? " FOR UPDATE" : "");
    List<Map<String, Object>> rows = jdbc.queryForList(query, id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private void requireVisible(SessionPrincipal principal, Map<String, Object> row) {
    if (!ReportScopePolicy.canAccess(
        principal,
        ((Number) row.get("member_id")).longValue(),
        ((Number) row.get("direction_id")).longValue(),
        "DRAFT".equals(row.get("status"))))
      throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private void requireRequestedMember(SessionPrincipal principal, long memberId) {
    if ("COLLEGE_ADMIN".equals(principal.role())) return;
    if (principal.memberId() != null && principal.memberId() == memberId) return;
    if ("DIRECTION_LEADER".equals(principal.role())) {
      if (principal.managedDirectionIds().isEmpty()) throw new ApiException(403, ErrorCode.FORBIDDEN);
      String placeholders =
          String.join(",", java.util.Collections.nCopies(principal.managedDirectionIds().size(), "?"));
      Integer currentDirection =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM member WHERE id=? AND direction_id IN (" + placeholders + ")",
              Integer.class,
              concat(memberId, principal.managedDirectionIds()));
      Integer historicalDirection =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM report WHERE member_id=? AND direction_id IN (" + placeholders + ")",
              Integer.class,
              concat(memberId, principal.managedDirectionIds()));
      if ((currentDirection != null && currentDirection > 0)
          || (historicalDirection != null && historicalDirection > 0)) return;
    }
    throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private void requireRequestedDirection(SessionPrincipal principal, long directionId) {
    if ("COLLEGE_ADMIN".equals(principal.role())
        || (principal.memberId() != null
            && jdbc.queryForObject(
                    "SELECT direction_id FROM member WHERE id=?", Long.class, principal.memberId())
                == directionId)
        || ("DIRECTION_LEADER".equals(principal.role())
            && principal.managedDirectionIds().contains(directionId))) return;
    throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private void requireReadyRules() {
    pointsPolicy.validate();
    if (!"READY".equals(props.getPointsPolicyStatus())
        || (props.getDailyBasePoints() < 1 || props.getDailyBasePoints() > 3)
        || (props.getWeeklyBasePoints() < 1 || props.getWeeklyBasePoints() > 3))
      throw new ApiException(503, ErrorCode.RULES_NOT_CONFIGURED);
  }

  private void requireCurrentPeriod(long id, Map<String, Object> row) {
    ReportType type = ReportType.valueOf((String) row.get("report_type"));
    LocalDate current = periods.dailyPeriod();
    boolean currentPeriod =
        type == ReportType.DAILY
            ? current.toString().equals(date(row.get("report_date")))
            : periods.weeklyPeriod().start().toString().equals(date(row.get("week_start")));
    if (!currentPeriod) throw new ApiException(409, ErrorCode.INVALID_STATE);
  }

  private String reportPath(long id) {
    return ReportType.valueOf((String) locked(id, false).get("report_type")) == ReportType.DAILY
        ? "daily-reports"
        : "weekly-reports";
  }

  private Map<String, Object> normalizeContent(
      Map<String, Object> request, ReportType type, boolean create) {
    Set<String> allowed =
        type == ReportType.DAILY
            ? Set.of("reportDate", "learningContent", "gains", "problems", "nextPlan", "links")
            : Set.of("weekStart", "summary", "achievements", "reflections", "nextWeekPlan", "links");
    if (!allowed.containsAll(request.keySet())) throw bad();
    Map<String, Object> content = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : request.entrySet()) {
      String key = entry.getKey();
      if (key.equals("reportDate") || key.equals("weekStart") || key.equals("weekEnd")) continue;
      Object value = entry.getValue();
      if ("links".equals(key)) content.put(key, links(value));
      else content.put(key, text(value, limit(type, key)));
    }
    if (create) {
      if (type == ReportType.DAILY) {
        content.putIfAbsent("learningContent", "");
        content.putIfAbsent("gains", "");
        content.putIfAbsent("problems", null);
        content.putIfAbsent("nextPlan", null);
      } else {
        content.putIfAbsent("summary", "");
        content.putIfAbsent("achievements", "");
        content.putIfAbsent("reflections", null);
        content.putIfAbsent("nextWeekPlan", null);
      }
      content.putIfAbsent("links", new ArrayList<>());
    }
    return content;
  }

  private void normalizeMerged(Map<String, Object> content, ReportType type) {
    Set<String> allowed =
        type == ReportType.DAILY
            ? Set.of("learningContent", "gains", "problems", "nextPlan", "links")
            : Set.of("summary", "achievements", "reflections", "nextWeekPlan", "links");
    if (!allowed.containsAll(content.keySet())) throw bad();
    for (String key : allowed) {
      if (!content.containsKey(key)) {
        content.put(key, "links".equals(key) ? new ArrayList<>() : key.equals("gains") || key.equals("achievements") || key.equals("learningContent") || key.equals("summary") ? "" : null);
      }
    }
  }

  private String text(Object value, int limit) {
    if (value == null) return null;
    if (!(value instanceof String text) || text.length() > limit) throw bad();
    return text;
  }

  private List<String> links(Object value) {
    if (value == null) return new ArrayList<>();
    if (!(value instanceof List<?> list) || list.size() > 10) throw bad();
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String link) || link.length() > 2048) throw bad();
      try {
        URI uri = URI.create(link);
        if (!uri.isAbsolute() || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
          throw bad();
      } catch (IllegalArgumentException error) {
        throw bad();
      }
      result.add(link);
    }
    return result;
  }

  private int limit(ReportType type, String key) {
    if (key.equals("learningContent") || key.equals("gains") || key.equals("summary") || key.equals("achievements"))
      return 10000;
    return 5000;
  }

  private LocalDateTime periodEnd(ReportType type, Map<String, Object> row) {
    Object rawDay = type == ReportType.DAILY ? row.get("report_date") : row.get("week_end");
    LocalDate day =
        rawDay instanceof LocalDate localDate
            ? localDate
            : rawDay instanceof java.sql.Date sqlDate
                ? sqlDate.toLocalDate()
                : LocalDate.parse(rawDay.toString());
    return day.atTime(23, 59, 59).atZone(BUSINESS_ZONE).toLocalDateTime();
  }

  private LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException error) {
      throw bad();
    }
  }

  private boolean validScoreStatus(String value) {
    return Set.of(
            "NOT_REQUESTED",
            "QUEUED",
            "PROCESSING",
            "SCORED",
            "RETRYING",
            "REVIEW_REQUIRED",
            "MANUAL")
        .contains(value);
  }

  private String timestamp(Object value) {
    return value == null ? null : OFFSET.format(instant(value).atZone(BUSINESS_ZONE));
  }

  private Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof Instant temporal) return temporal;
    throw new IllegalStateException("Unsupported timestamp");
  }

  private String date(Object value) {
    return value == null ? null : value.toString();
  }

  private static Object[] append(List<Object> args, Object... extra) {
    Object[] result = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) result[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) result[args.size() + i] = extra[i];
    return result;
  }

  private static Object[] concat(long first, List<Long> values) {
    Object[] result = new Object[values.size() + 1];
    result[0] = first;
    for (int i = 0; i < values.size(); i++) result[i + 1] = values.get(i);
    return result;
  }

  private static void validatePage(int page, int size) {
    if (page < 1 || size < 1 || size > 200) throw bad();
  }

  public static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
