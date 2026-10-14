package io.github.dream07code.innovationspace.report;
import java.time.*;
public final class ReportPeriodPolicy {
 public record WeekPeriod(LocalDate start,LocalDate end){}
 private final Clock clock;public ReportPeriodPolicy(Clock clock){this.clock=clock.withZone(ZoneId.of("Asia/Shanghai"));}
 public LocalDate dailyPeriod(){return LocalDate.now(clock);}
 public WeekPeriod weeklyPeriod(){var date=dailyPeriod();var start=date.with(java.time.DayOfWeek.MONDAY);return new WeekPeriod(start,start.plusDays(6));}
 public boolean isEditable(ReportStatus status,Instant editableUntil,Instant now){return status==ReportStatus.DRAFT||(editableUntil!=null&&now.isBefore(editableUntil));}
}
