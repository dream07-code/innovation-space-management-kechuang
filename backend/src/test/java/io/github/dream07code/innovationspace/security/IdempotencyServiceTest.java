package io.github.dream07code.innovationspace.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdempotencyServiceTest {
    @Test void equivalentJsonHasSameDigestWithoutIgnoringNullOrArrays() throws Exception {
        var type=Class.forName("io.github.dream07code.innovationspace.security.RequestDigest");
        var method=type.getMethod("digest",String.class);
        assertEquals(method.invoke(null,"{\"b\":2,\"a\":1}"),method.invoke(null,"{ \"a\":1,\"b\":2 }"));
        assertNotEquals(method.invoke(null,"{}"),method.invoke(null,"{\"a\":null}"));
        assertNotEquals(method.invoke(null,"{\"a\":[1,2]}"),method.invoke(null,"{\"a\":[2,1]}"));
    }
}
