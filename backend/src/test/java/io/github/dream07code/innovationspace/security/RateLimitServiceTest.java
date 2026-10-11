package io.github.dream07code.innovationspace.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitServiceTest {
    @Test void bucketCapacityAndFixedWindowAreBounded() throws Exception {
        var type=Class.forName("io.github.dream07code.innovationspace.security.RateLimitService");
        var service=type.getConstructor(java.time.Clock.class).newInstance(java.time.Clock.fixed(java.time.Instant.EPOCH,java.time.ZoneOffset.UTC));
        var allow=type.getMethod("check",String.class);
        for(int i=0;i<10;i++)allow.invoke(service,"synthetic-key");
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->allow.invoke(service,"synthetic-key"));
    }
}
