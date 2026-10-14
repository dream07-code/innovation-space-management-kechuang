package io.github.dream07code.innovationspace.points;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointsPolicyPropertiesTest {
  @Test
  void confirmedPolicySupportsBothNamedModesWithBoundedFinalScore() {
    var policy = new PointsPolicyProperties();
    assertDoesNotThrow(policy::validate);
    policy.setPointsPolicyStatus("READY");
    policy.setPointsMode("BASE_PLUS_AI");
    policy.setMaxFinalScore(4);
    assertDoesNotThrow(policy::validate);
    policy.setPointsMode("UNKNOWN");
    assertThrows(IllegalArgumentException.class, policy::validate);
    policy.setPointsMode("BASE_PLUS_AI");
    policy.setMaxFinalScore(9);
    assertThrows(IllegalArgumentException.class, policy::validate);
  }
}
