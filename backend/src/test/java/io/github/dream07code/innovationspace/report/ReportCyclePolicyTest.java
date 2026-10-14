package io.github.dream07code.innovationspace.report;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReportCyclePolicyTest {
 @Test void shanghaiClockDeterminesDailyAndWeeklyPeriods(){
  var clock=Clock.fixed(Instant.parse("2026-10-11T00:30:00Z"),ZoneId.of("Asia/Shanghai"));
  var policy=new ReportPeriodPolicy(clock);
  assertEquals(LocalDate.of(2026,10,11),policy.dailyPeriod());
  assertEquals(LocalDate.of(2026,10,5),policy.weeklyPeriod().start());assertEquals(LocalDate.of(2026,10,11),policy.weeklyPeriod().end());
 }
 @Test void submittedReportCannotBeEditedAfterEditableWindow(){
  var policy=new ReportPeriodPolicy(Clock.systemUTC());assertFalse(policy.isEditable(ReportStatus.SUBMITTED,Instant.now().minusSeconds(1),Instant.now()));
  assertTrue(policy.isEditable(ReportStatus.DRAFT,null,Instant.now()));
 }
}
