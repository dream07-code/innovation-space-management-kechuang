package io.github.dream07code.innovationspace.points;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RankingService {
  private static final DateTimeFormatter OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public RankingService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Map<String, Object> overall(int page, int size, String period, String month) {
    return query(page, size, null, period, month);
  }

  public Map<String, Object> direction(long direction, int page, int size, String period, String month) {
    return query(page, size, direction, period, month);
  }

  public Map<String, Object> me(SessionPrincipal principal, String period, String month) {
    if (!"STUDENT".equals(principal.role()) || principal.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    Period selected = period(period, month);
    Map<String, Object> row =
        jdbc.queryForMap("SELECT id,direction_id,membership_status FROM member WHERE id=?", principal.memberId());
    long points = selected.month() == null
        ? jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=?", Long.class, principal.memberId())
        : jdbc.queryForObject(
            "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=? AND business_effective_at>=? AND business_effective_at<?",
            Long.class,
            principal.memberId(),
            Timestamp.from(selected.from()),
            Timestamp.from(selected.to()));
    boolean eligible = "ACTIVE".equals(row.get("membership_status"));
    long direction = ((Number) row.get("direction_id")).longValue();
    Long overallRank = eligible ? rank(points, null, selected) : null;
    Long directionRank = eligible ? rank(points, direction, selected) : null;
    int overallCount = count(null, selected);
    int directionCount = count(direction, selected);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("memberId", String.valueOf(row.get("id")));
    out.put("directionId", String.valueOf(row.get("direction_id")));
    out.put("period", selected.name());
    out.put("month", selected.month());
    out.put("points", points);
    out.put("overallRank", overallRank);
    out.put("directionRank", directionRank);
    out.put("overallCount", overallCount);
    out.put("directionCount", directionCount);
    out.put("eligible", eligible);
    out.put("asOf", asOf());
    return out;
  }

  private Map<String, Object> query(int page, int size, Long direction, String period, String month) {
    if (page < 1 || size < 1 || size > 200) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
    Period selected = period(period, month);
    Query query = scored(direction, selected);
    String ranked =
        "SELECT member_id,display_name,direction_id,direction_name,points,"
            + "RANK() OVER (ORDER BY points DESC) rank_num FROM (" + query.sql() + ") scored";
    String paged = "SELECT * FROM (" + ranked + ") ranked ORDER BY points DESC,member_id ASC LIMIT ? OFFSET ?";
    List<Object> pageArgs = new ArrayList<>(query.args());
    pageArgs.add(size);
    pageArgs.add((page - 1) * size);
    List<Map<String, Object>> rows = jdbc.queryForList(paged, pageArgs.toArray());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("memberId", String.valueOf(row.get("member_id")));
      item.put("displayName", row.get("display_name"));
      item.put("directionId", String.valueOf(row.get("direction_id")));
      item.put("directionName", row.get("direction_name"));
      item.put("points", ((Number) row.get("points")).intValue());
      item.put("rank", ((Number) row.get("rank_num")).intValue());
      items.add(item);
    }
    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + query.sql() + ") scored", Long.class, query.args().toArray());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("page", page);
    result.put("size", size);
    result.put("total", total.intValue());
    result.put("period", selected.name());
    result.put("month", selected.month());
    result.put("asOf", asOf());
    return result;
  }

  private long rank(long points, Long direction, Period period) {
    Query query = scored(direction, period);
    Long greater =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (" + query.sql() + ") scored WHERE points>?",
            Long.class,
            append(query.args(), points));
    return greater + 1;
  }

  private int count(Long direction, Period period) {
    Query query = scored(direction, period);
    return jdbc.queryForObject("SELECT COUNT(*) FROM (" + query.sql() + ") scored", Integer.class, query.args().toArray());
  }

  private Query scored(Long direction, Period period) {
    StringBuilder where =
        new StringBuilder(" WHERE u.role='STUDENT' AND m.membership_status='ACTIVE'");
    List<Object> args = new ArrayList<>();
    if (direction != null) {
      where.append(" AND m.direction_id=?");
      args.add(direction);
    }
    if (period.month() != null) {
      where.append(" AND pl.business_effective_at>=? AND pl.business_effective_at<?");
      args.add(Timestamp.from(period.from()));
      args.add(Timestamp.from(period.to()));
    }
    String sql =
        "SELECT m.id member_id,m.name display_name,m.direction_id,d.name direction_name,"
            + "COALESCE(SUM(pl.delta),0) points FROM member m "
            + "JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id "
            + "LEFT JOIN point_ledger pl ON pl.member_id=m.id"
            + where
            + " GROUP BY m.id,m.name,m.direction_id,d.name";
    return new Query(sql, args);
  }

  private record Query(String sql, List<Object> args) {}

  private record Period(String name, String month, java.time.Instant from, java.time.Instant to) {}

  private Period period(String value, String month) {
    String normalized = value == null || value.isBlank() ? "ALL_TIME" : value;
    if (!normalized.equals("ALL_TIME") && !normalized.equals("MONTH")) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
    if (normalized.equals("ALL_TIME")) {
      if (month != null && !month.isBlank()) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
      return new Period("ALL_TIME", null, null, null);
    }
    if (month == null || month.isBlank()) throw new ApiException(400, ErrorCode.INVALID_REQUEST);
    MonthFilter filter = MonthFilter.parse(month, clock);
    return new Period("MONTH", filter.sqlMonth(), filter.startUtc(), filter.endUtc());
  }

  private String asOf() {
    return OFFSET.format(clock.instant().atZone(ZoneId.of("Asia/Shanghai")));
  }

  private static Object[] append(List<Object> args, Object... extra) {
    Object[] result = new Object[args.size() + extra.length];
    for (int i = 0; i < args.size(); i++) result[i] = args.get(i);
    for (int i = 0; i < extra.length; i++) result[args.size() + i] = extra[i];
    return result;
  }
}
