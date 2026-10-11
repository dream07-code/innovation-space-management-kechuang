package io.github.dream07code.innovationspace.security;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class PasswordService {
    private final PasswordEncoder encoder;
    public PasswordService() {
        this.encoder = new Pbkdf2PasswordEncoder("", 16, 600_000, 256);
    }
    public String encode(CharSequence raw) { return "{pbkdf2}" + encoder.encode(raw); }
    public boolean matches(CharSequence raw, String encoded) {
        return encoded != null && encoded.startsWith("{pbkdf2}") && encoder.matches(raw, encoded.substring("{pbkdf2}".length()));
    }
    public void validateNewPassword(String raw, String currentHash, String initialPassword) {
        if (raw == null || raw.codePointCount(0, raw.length()) < 12 || raw.codePointCount(0, raw.length()) > 64
                || raw.isBlank() || raw.equals(initialPassword) || matches(raw, currentHash)) {
            throw new IllegalArgumentException("Password policy violation");
        }
    }
}
