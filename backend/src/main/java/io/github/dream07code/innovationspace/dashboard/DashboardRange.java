package io.github.dream07code.innovationspace.dashboard;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public record DashboardRange(
    String raw, LocalDate fromInclusive, LocalDate toInclusive, int days) {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  public DashboardRange {
    if (!"7d".equals(raw) && !"30d".equals(raw)) throw bad();
    if (fromInclusive == null || toInclusive == null || days < 1
        || !fromInclusive.plusDays(days - 1).equals(toInclusive)) throw bad();
  }

  public static DashboardRange lastDays(String raw, Clock clock) {
    if (!"7d".equals(raw) && !"30d".equals(raw)) throw bad();
    int days = Integer.parseInt(raw.substring(0, raw.length() - 1));
    LocalDate today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
    return new DashboardRange(raw, today.minusDays(days - 1), today, days);
  }

  public static DashboardRange parse(String value, DashboardRange defaultRange) {
    if (value == null || value.isBlank()) return defaultRange;
    if (value.equals(defaultRange.raw())) return defaultRange;
    int days = "7d".equals(value) ? 7 : "30d".equals(value) ? 30 : -1;
    if (days < 0) throw bad();
    return new DashboardRange(value, defaultRange.toInclusive().minusDays(days - 1),
        defaultRange.toInclusive(), days);
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
