package io.github.dream07code.innovationspace.points;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PointLedgerService {
  private static final DateTimeFormatter OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public PointLedgerService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public long total(long member) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=?", Long.class, member);
  }

  public long total(long member, MonthFilter month) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=? AND business_effective_at>=? AND business_effective_at<?",
        Long.class, member, Timestamp.from(month.startUtc()), Timestamp.from(month.endUtc()));
  }

  public Map<String, Object> summary(long member, String monthValue) {
    MonthFilter month = MonthFilter.parse(monthValue, clock);
    return Map.of(
        "memberId", Long.toString(member),
        "totalPoints", total(member),
        "month", month.sqlMonth(),
        "monthlyPoints", total(member, month),
        "asOf", OFFSET.format(clock.instant().atZone(ZoneId.of("Asia/Shanghai"))));
  }

  public Map<String, Object> ledger(
      SessionPrincipal principal, Long requestedMember, String monthValue, int page, int size) {
    validatePage(page, size);
    long member = resolveMember(principal, requestedMember);
    MonthFilter month = monthValue == null || monthValue.isBlank() ? null : MonthFilter.parse(monthValue, clock);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE pl.member_id=?");
    args.add(member);
    if (month != null) {
      where.append(" AND pl.business_effective_at>=? AND pl.business_effective_at<?");
      args.add(Timestamp.from(month.startUtc()));
      args.add(Timestamp.from(month.endUtc()));
    }
    String from =
        " FROM point_ledger pl LEFT JOIN report r ON r.id=pl.report_id LEFT JOIN report_version v ON v.id=pl.report_version_id"
            + where;
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT pl.id,pl.member_id,COALESCE(r.report_type,'DAILY') report_type,pl.report_id,COALESCE(v.version,1) report_version,"
                + "pl.delta,pl.source,pl.business_effective_at,pl.recorded_at,pl.rule_version"
                + from
                + " ORDER BY pl.recorded_at DESC,pl.id DESC LIMIT ? OFFSET ?",
            append(args, size, (page - 1) * size));
    Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : rows) items.add(mapEntry(row));
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  public void appendIfAbsent(
      long member,
      long report,
      long version,
      int delta,
      String source,
      String eventKey,
      Instant effective) {
    if (eventKey == null || eventKey.isBlank()) throw new IllegalArgumentException("eventKey");
    try {
      jdbc.update(
          "INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(?,?,?,?,?,?,?,?,?)",
          member,
          report,
          version,
          delta,
          source,
          Timestamp.from(effective),
          Timestamp.from(clock.instant()),
          "v1",
          eventKey);
    } catch (DuplicateKeyException duplicate) {
      // Concurrent workers may observe the same version. The unique event is the source of truth.
    }
  }

  private long resolveMember(SessionPrincipal principal, Long requested) {
    if (requested == null) {
      if (principal.memberId() == null) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
      return principal.memberId();
    }
    if (principal.memberId() != null && principal.memberId() == requested) return requested;
    if ("COLLEGE_ADMIN".equals(principal.role())) return requested;
    if ("DIRECTION_LEADER".equals(principal.role())) {
      if (principal.managedDirectionIds().isEmpty()) throw new ApiException(403, ErrorCode.FORBIDDEN);
      String placeholders =
          String.join(",", java.util.Collections.nCopies(principal.managedDirectionIds().size(), "?"));
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM member WHERE id=? AND direction_id IN (" + placeholders + ")",
              Integer.class,
              concat(requested, principal.managedDirectionIds()));
      if (count != null && count > 0) return requested;
    }
    throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private Map<String, Object> mapEntry(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("reportType", row.get("report_type"));
    out.put("reportId", row.get("report_id") == null ? null : String.valueOf(row.get("report_id")));
    out.put("reportVersion", ((Number) row.get("report_version")).intValue());
    out.put("delta", ((Number) row.get("delta")).intValue());
    out.put("source", row.get("source"));
    out.put("reason", reason((String) row.get("source")));
    out.put("effectiveAt", timestamp(row.get("business_effective_at")));
    out.put("recordedAt", timestamp(row.get("recorded_at")));
    out.put("ruleVersion", row.get("rule_version"));
    return out;
  }

  private String reason(String source) {
    return switch (source) {
      case "SUBMISSION_BASE" -> "报告正式提交保底积分";
      case "AI_ADJUSTMENT" -> "报告评分调整";
      case "MANUAL_ADJUSTMENT" -> "人工积分调整";
      case "REVERSAL" -> "积分冲正";
      default -> "积分流水";
    };
  }

  private String timestamp(Object value) {
    Instant instant;
    if (value instanceof Timestamp timestamp) instant = timestamp.toInstant();
    else if (value instanceof LocalDateTime local) instant = local.toInstant(ZoneOffset.UTC);
    else if (value instanceof Instant temporal) instant = temporal;
    else throw new IllegalStateException("Unsupported database timestamp");
    return OFFSET.format(instant.atZone(ZoneId.of("Asia/Shanghai")));
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
    if (page < 1 || size < 1 || size > 200) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
