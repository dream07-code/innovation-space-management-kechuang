package io.github.dream07code.innovationspace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withUserConfiguration(DatabaseConfiguration.class);

    @Test
    void credentialsMustBeProvided() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingUsernameFailsWithoutLoggingThePassword(CapturedOutput output) {
        runner.withPropertyValues("DB_PASSWORD=configuration-test-password")
                .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain("configuration-test-password");
    }

    @Test
    void missingPasswordFails() {
        runner.withPropertyValues("DB_USERNAME=bootstrap_user")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankUsernameFails() {
        runner.withPropertyValues("DB_USERNAME=   ", "DB_PASSWORD=configuration-test-password")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankPasswordFails() {
        runner.withPropertyValues("DB_USERNAME=bootstrap_user", "DB_PASSWORD=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void providedCredentialsBindButDiagnosticsAreRedacted() {
        runner.withPropertyValues("DB_USERNAME=bootstrap_user", "DB_PASSWORD=configuration-test-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var credentials = context.getBean(DatabaseCredentialsProperties.class);
                    assertThat(credentials.username()).isEqualTo("bootstrap_user");
                    assertThat(credentials.password()).isEqualTo("configuration-test-password");
                    assertThat(credentials.toString()).doesNotContain("bootstrap_user", "configuration-test-password");
                    assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
                });
    }
}
