package io.github.dream07code.innovationspace.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {
    @Test void hashesAreSaltedAndPolicyCountsUnicodeCodePoints() throws Exception {
        var type=Class.forName("io.github.dream07code.innovationspace.security.PasswordService");
        var service=type.getDeclaredConstructor().newInstance();
        var encode=type.getMethod("encode",CharSequence.class);
        var matches=type.getMethod("matches",CharSequence.class,String.class);
        String hash=(String)encode.invoke(service,"合成密码😀abcdefghi");
        assertNotEquals(hash,encode.invoke(service,"合成密码😀abcdefghi"));
        assertTrue((boolean)matches.invoke(service,"合成密码😀abcdefghi",hash));
        assertFalse((boolean)matches.invoke(service,"wrong-value",hash));
        assertTrue(hash.startsWith("{pbkdf2}"));
        var validate=type.getMethod("validateNewPassword",String.class,String.class,String.class);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(service,"short",hash,"synthetic-initial"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(service,"合成密码😀abcdefghi",hash,"synthetic-initial"));
    }
}
