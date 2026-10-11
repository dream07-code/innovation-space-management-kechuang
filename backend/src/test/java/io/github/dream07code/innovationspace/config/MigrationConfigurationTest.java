package io.github.dream07code.innovationspace.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConfigurationTest {
    @Test void defaultWebStartupDoesNotMigrate() throws Exception {
        var type = Class.forName("io.github.dream07code.innovationspace.config.MigrationProperties");
        var properties = type.getDeclaredConstructor().newInstance();
        assertEquals("disabled", type.getMethod("getMode").invoke(properties));
        assertEquals("4", type.getMethod("getRequiredVersion").invoke(properties));
    }

    @Test void explicitMigrationAndUnknownModesHaveNoHiddenFallback() {
        var properties = new MigrationProperties();
        properties.setMode("migrate");
        assertEquals("migrate", properties.getMode());
        assertThrows(IllegalArgumentException.class, () -> properties.setMode("automatic"));
    }
}
