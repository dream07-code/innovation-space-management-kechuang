package io.github.dream07code.innovationspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class InnovationSpaceApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(InnovationSpaceApplication.class);
        boolean maintenance = java.util.Arrays.asList(args).contains("--app.migration.mode=migrate")
                || "migrate".equals(System.getenv("APP_MIGRATION_MODE"));
        if (maintenance) app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        var context = app.run(args);
        if (maintenance) context.close();
    }
}
