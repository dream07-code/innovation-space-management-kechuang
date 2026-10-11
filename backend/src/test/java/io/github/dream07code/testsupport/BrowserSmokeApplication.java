package io.github.dream07code.testsupport;

import io.github.dream07code.innovationspace.InnovationSpaceApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Manual browser fixture. Never part of the executable production JAR. */
@Configuration(proxyBeanMethods = false)
@Import({InnovationSpaceApplication.class, WebProbeConfiguration.class, OwnedDataSourceConfiguration.class})
public class BrowserSmokeApplication {
    public static void main(String[] args) throws Exception {
        var ready = Path.of("target/browser-smoke.json");
        Files.deleteIfExists(ready);
        try (var mysql = MysqlTestSupport.newMysqlContainer()) {
            var running = new AtomicReference<ConfigurableApplicationContext>();
            Runnable cleanup = () -> {
                var context = running.getAndSet(null);
                if (context != null) context.close();
                mysql.stop();
                try { Files.deleteIfExists(ready); } catch (java.io.IOException ignored) { }
            };
            var hook = new Thread(cleanup, "browser-smoke-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                mysql.start();
                var environment = new StandardEnvironment();
                environment.setActiveProfiles("dev");
                environment.getPropertySources().addFirst(new MapPropertySource("owned-smoke", Map.of(
                        "spring.profiles.active", "dev",
                        "spring.datasource.url", mysql.getJdbcUrl(),
                        "spring.datasource.username", mysql.getUsername(),
                        "spring.datasource.password", mysql.getPassword(),
                        "server.address", "127.0.0.1",
                        "server.port", "0",
                        "springdoc.packages-to-scan", "io.github.dream07code.testsupport")));
                var application = new SpringApplication(BrowserSmokeApplication.class);
                application.setEnvironment(environment);
                // We own context cleanup; avoid two concurrent shutdown hooks closing the same context.
                application.setRegisterShutdownHook(false);
                var context = application.run();
                running.set(context);
                var port = context.getEnvironment().getRequiredProperty("local.server.port");
                Files.createDirectories(ready.getParent());
                Files.writeString(ready, "{\"baseUrl\":\"http://127.0.0.1:" + port + "\"}");
                new java.util.concurrent.CountDownLatch(1).await();
            } finally {
                cleanup.run();
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
            }
        }
    }
}
