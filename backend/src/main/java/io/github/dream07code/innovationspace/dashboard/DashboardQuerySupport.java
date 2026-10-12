package io.github.dream07code.innovationspace.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class DashboardQuerySupport {
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private DashboardQuerySupport() {}

  static Timestamp businessStart(DashboardRange range) {
    return Timestamp.from(range.fromInclusive().atStartOfDay(BUSINESS_ZONE).toInstant());
  }

  static Timestamp businessEndExclusive(DashboardRange range) {
    return Timestamp.from(range.toInclusive().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
  }

  static Timestamp businessTodayStart(LocalDate date) {
    return Timestamp.from(date.atStartOfDay(BUSINESS_ZONE).toInstant());
  }

  static String date(Object value) {
    if (value == null) return null;
    return String.valueOf(value).substring(0, 10);
  }

  static String time(Object value) {
    if (value == null) return null;
    Instant instant =
        value instanceof Timestamp timestamp
            ? timestamp.toInstant()
            : value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC)
                : (Instant) value;
    return instant.atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
  }

  static String id(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static int intValue(Object value) {
    return value == null ? 0 : ((Number) value).intValue();
  }

  static long longValue(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }

  static double rate(long completed, long possible) {
    if (possible <= 0) return 0D;
    return Math.round(Math.min(1D, completed / (double) possible) * 10000D) / 100D;
  }
}
