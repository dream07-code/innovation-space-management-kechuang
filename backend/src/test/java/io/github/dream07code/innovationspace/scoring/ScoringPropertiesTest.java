package io.github.dream07code.innovationspace.scoring;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoringPropertiesTest {
  @Test
  void disabledNeedsNoSecretButEnabledNeedsSafeProviderConfig() {
    var properties = new ScoringProperties();
    properties.setEnabled(false);
    assertDoesNotThrow(properties::validate);
    properties.setEnabled(true);
    assertThrows(IllegalArgumentException.class, properties::validate);
    properties.setProvider("mock");
    properties.setModel("mock-model");
    properties.setApiKey("secret");
    properties.validate();
    assertFalse(properties.toString().contains("secret"));
  }

  @Test
  void publicBaseUrlMustBePureOriginAndNumericLimitsAreBounded() {
    var properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("openai-compatible");
    properties.setModel("model");
    properties.setApiKey("synthetic");
    properties.setBaseUrl("https://user:pass@example.com/path?token=x#frag");
    assertThrows(IllegalArgumentException.class, properties::validate);
    properties.setBaseUrl("http://example.com");
    assertThrows(IllegalArgumentException.class, properties::validate);
    properties.setBaseUrl("http://127.0.0.1:8080");
    assertDoesNotThrow(properties::validate);
    properties.setConnectTimeoutMs(99);
    assertThrows(IllegalArgumentException.class, properties::validate);
    properties.setConnectTimeoutMs(3000);
    properties.setRequestTimeoutMs(200000);
    assertThrows(IllegalArgumentException.class, properties::validate);
    properties.setRequestTimeoutMs(30000);
    properties.setMaxConcurrency(999);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
