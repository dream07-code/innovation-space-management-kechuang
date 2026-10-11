package io.github.dream07code.innovationspace.scoring;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ScoringPropertiesTest { @Test void disabledNeedsNoSecretButEnabledNeedsSafeProviderConfig(){var p=new ScoringProperties();p.setEnabled(false);assertDoesNotThrow(p::validate);p.setEnabled(true);assertThrows(IllegalArgumentException.class,p::validate);p.setProvider("mock");p.setModel("mock-model");p.setApiKey("secret");p.validate();assertFalse(p.toString().contains("secret"));}}
