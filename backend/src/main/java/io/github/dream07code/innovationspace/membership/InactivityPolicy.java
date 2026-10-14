package io.github.dream07code.innovationspace.membership;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public final class InactivityPolicy {
  public static final int THRESHOLD_DAYS = 14;
  public static final String RULE_VERSION = "INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1";

  public record Evaluation(LocalDate referenceDate, int completedInactiveDays, boolean removable) {}

  public Evaluation evaluate(
      LocalDate businessDate, LocalDate membershipStartedAt, LocalDate lastSubmittedAt) {
    if (businessDate == null || membershipStartedAt == null)
      throw new IllegalArgumentException("Business and membership dates are required");
    LocalDate reference =
        lastSubmittedAt == null || lastSubmittedAt.isBefore(membershipStartedAt)
            ? membershipStartedAt
            : lastSubmittedAt;
    long completed = Math.max(0, ChronoUnit.DAYS.between(reference.plusDays(1), businessDate));
    int days = completed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) completed;
    return new Evaluation(reference, days, days >= THRESHOLD_DAYS);
  }
}
