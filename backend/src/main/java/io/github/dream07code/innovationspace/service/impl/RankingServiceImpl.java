package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.RankingService;
import io.github.dream07code.innovationspace.points.*;


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
public class RankingServiceImpl implements RankingService {
  private static final DateTimeFormatter OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public RankingServiceImpl(JdbcTemplate jdbc, Clock clock) {
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
    // 月度榜的集合只含「当月有积分流水」的人（见 scored 的时间条件），
    // 所以没当月经分的人不在榜上：名次必须是 null，不能按「比我高的人数+1」算，
    // 否则会出现「4 / 3」这种名次大于总人数的结果。
    boolean onBoard = selected.month() == null || hasPointsIn(principal.memberId(), selected);
    Long overallRank = eligible && onBoard ? rank(points, null, selected) : null;
    Long directionRank = eligible && onBoard ? rank(points, direction, selected) : null;
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

  /**
   * 该成员在统计窗口内是否有积分流水（哪怕净额为 0）。
   *
   * <p>月度榜只收有当月流水的人，没流水的人压根不在集合里；用它来判断「是否上榜」，
   * 保证名次和总人数用的是同一个集合。
   */
  private boolean hasPointsIn(long memberId, Period period) {
    if (period.month() == null) return true;
    Long rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE member_id=?"
                + " AND business_effective_at>=? AND business_effective_at<?",
            Long.class,
            memberId,
            Timestamp.from(period.from()),
            Timestamp.from(period.to()));
    return rows != null && rows > 0;
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
    String perMember =
        "SELECT m.id member_id,m.name display_name,m.direction_id,d.name direction_name,"
            + "COALESCE(SUM(pl.delta),0) points FROM member m "
            + "JOIN app_user u ON u.member_id=m.id JOIN direction d ON d.id=m.direction_id "
            + "LEFT JOIN point_ledger pl ON pl.member_id=m.id"
            + where
            + " GROUP BY m.id,m.name,m.direction_id,d.name";
    // 同一姓名在历史测试数据里会对应多个 member 档案（同一场景被反复建人），
    // 按 member.id 出榜就会一个名字刷出十几行、还把同一个人的分数拆开。
    // 榜单按姓名收成一行：取该姓名下积分最高的档案作为代表，同分取 id 小的，
    // 保证同一份数据每次排序结果一致；积分不做跨档案相加，避免重复建人把分数堆高。
    String sql =
        "SELECT member_id,display_name,direction_id,direction_name,points FROM ("
            + " SELECT per.*,ROW_NUMBER() OVER ("
            + " PARTITION BY per.display_name ORDER BY per.points DESC,per.member_id ASC"
            + " ) rn FROM (" + perMember + ") per"
            + ") dedup WHERE rn=1";
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
