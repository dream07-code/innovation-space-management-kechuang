package io.github.dream07code.innovationspace.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("spring.datasource")
@Validated
public record DatabaseCredentialsProperties(@NotBlank String username, @NotBlank String password) {
    public DatabaseCredentialsProperties {
        // Configuration binding otherwise retains unresolved placeholders as strings.
        if ("${DB_USERNAME}".equals(username) || "${DB_PASSWORD}".equals(password)) {
            throw new IllegalArgumentException("DB_USERNAME and DB_PASSWORD must be supplied");
        }
    }

    @Override
    public String toString() {
        return "DatabaseCredentialsProperties[username=<redacted>, password=<redacted>]";
    }
}
