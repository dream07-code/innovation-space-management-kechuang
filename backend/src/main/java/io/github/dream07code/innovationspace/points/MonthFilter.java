package io.github.dream07code.innovationspace.points;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public record MonthFilter(String month, Instant fromInclusive, Instant toExclusive) {
  public static MonthFilter parse(String value, Clock clock) {
    YearMonth parsed;
    if (value == null || value.isBlank()) {
      parsed = YearMonth.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai")));
    } else {
      try {
        parsed = YearMonth.parse(value);
      } catch (DateTimeParseException error) {
        throw new ApiException(400, ErrorCode.INVALID_REQUEST);
      }
    }
    Instant from = parsed.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    Instant to = parsed.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    return new MonthFilter(parsed.toString(), from, to);
  }

  public Instant startUtc() { return fromInclusive; }
  public Instant endUtc() { return toExclusive; }
  public String sqlMonth() { return month; }
}
