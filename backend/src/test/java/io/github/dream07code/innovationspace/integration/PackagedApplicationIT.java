package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.DocumentationTestSupport;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Starts the executable artifact, not the test classpath, against an owned database. */
@Testcontainers
class PackagedApplicationIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");

    @ParameterizedTest
    @ValueSource(strings = {"default", "dev", "prod", "prod,dev", "dev,prod"})
    void artifactHasNoProbeAndHonorsProfileIsolation(String profile) throws Exception {
        MysqlTestSupport.migrate(MYSQL);
        Files.createDirectories(Path.of("target/verification"));
        var log = Path.of("target/verification/jar-" + profile.replace(',', '-') + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "target/innovation-space-backend-0.1.0-SNAPSHOT.jar",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=" + profile);
        var environment = builder.environment();
        environment.clear();
        environment.put("DB_HOST", MYSQL.getHost());
        environment.put("DB_PORT", MYSQL.getMappedPort(3306).toString());
        environment.put("DB_NAME", MYSQL.getDatabaseName());
        environment.put("DB_USERNAME", MYSQL.getUsername());
        environment.put("DB_PASSWORD", MYSQL.getPassword());
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            int port = awaitPort(process, log);
            var health = HttpTestSupport.get(port, "/actuator/health");
            assertEquals(200, health.statusCode());
            assertEquals("UP", HttpTestSupport.json(health).path("status").asText());
            assertEquals(404, HttpTestSupport.get(port, "/api/v1/test-probe/ping").statusCode());
            assertEquals(404, HttpTestSupport.get(port, "/api/v1/register").statusCode());
            if (profile.equals("dev")) {
                assertEquals(200, HttpTestSupport.get(port, "/doc.html").statusCode());
                var response = HttpTestSupport.get(port, "/v3/api-docs/innovation-space");
                assertEquals(200, response.statusCode());
                var api = HttpTestSupport.json(response);
                assertEquals("AI 编程实训助手 API", api.at("/info/title").asText());
                assertTrue(api.path("paths").isObject());
                assertTrue(api.path("paths").has("/api/v1/auth/login"));
            } else {
                DocumentationTestSupport.assertDocumentationDisabled(port);
            }
            assertFalse(Files.readString(log).contains(MYSQL.getPassword()));
        } finally {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned JAR process did not stop");
            }
        }
    }

    private static int awaitPort(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), "owned JAR exited before startup; see " + log);
            var matcher = PORT.matcher(Files.readString(log));
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
            Thread.sleep(200);
        }
        throw new AssertionError("owned JAR did not start within 60 seconds; see " + log);
    }
}
