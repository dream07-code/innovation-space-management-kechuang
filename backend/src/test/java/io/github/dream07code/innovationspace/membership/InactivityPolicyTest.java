package io.github.dream07code.innovationspace.membership;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InactivityPolicyTest {
  private final InactivityPolicy policy = new InactivityPolicy();

  @Test
  void completeDaysUseShanghaiDateBoundaries() {
    var atDay15 = policy.evaluate(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 1), null);
    assertEquals(LocalDate.of(2026, 10, 1), atDay15.referenceDate());
    assertEquals(13, atDay15.completedInactiveDays());
    assertFalse(atDay15.removable());

    var atDay16 = policy.evaluate(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 1), null);
    assertEquals(14, atDay16.completedInactiveDays());
    assertTrue(atDay16.removable());

    var afterReport = policy.evaluate(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10));
    assertEquals(LocalDate.of(2026, 10, 10), afterReport.referenceDate());
    assertEquals(14, afterReport.completedInactiveDays());
    assertTrue(afterReport.removable());
  }

  @Test
  void reportOnBoundaryDateResetsTheCompleteDayCount() {
    var evaluation = policy.evaluate(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
    assertEquals(0, evaluation.completedInactiveDays());
    assertFalse(evaluation.removable());
  }
}
