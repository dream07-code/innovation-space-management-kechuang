package io.github.dream07code.innovationspace.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeConfigurationTest {
    @Test
    void businessClockUsesShanghai() {
        try (var context = new AnnotationConfigApplicationContext(TimeConfiguration.class)) {
            assertEquals(ZoneId.of("Asia/Shanghai"), context.getBean(Clock.class).getZone());
        }
    }

    @Test
    void fixedClockCrossesShanghaiMidnight() {
        try (var context = new AnnotationConfigApplicationContext(TimeConfiguration.class)) {
            var zone = context.getBean(Clock.class).getZone();
            var fixed = Clock.fixed(Instant.parse("2026-10-10T16:30:00Z"), zone);
            assertEquals(LocalDate.of(2026, 10, 11), LocalDate.now(fixed));
        }
    }
}
