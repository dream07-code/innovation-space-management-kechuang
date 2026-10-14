package io.github.dream07code.innovationspace.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {
    @Test void randomTokensAreStrictlyBoundedAndDiagnosticsAreRedacted() throws Exception {
        var type=Class.forName("io.github.dream07code.innovationspace.security.TokenService");
        var service=type.getDeclaredConstructor().newInstance();
        var token=type.getMethod("issuePasswordChange",long.class,long.class).invoke(service,1L,1L);
        String raw=(String)token.getClass().getMethod("raw").invoke(token);
        assertTrue(raw.matches("[A-Za-z0-9_-]{43}"));assertFalse(token.toString().contains(raw));
        String digest=(String)type.getMethod("digest",String.class).invoke(service,raw);
        assertEquals(64,digest.length());assertNotEquals(raw,digest);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->type.getMethod("validateSyntax",String.class).invoke(service,"bad-token"));
    }
}
