package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.HttpTestSupport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@org.springframework.context.annotation.Import(io.github.dream07code.testsupport.OwnedDataSourceConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnavailableDatabaseHealthIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final ServerSocket REJECTOR = rejectingServer();

    private static ServerSocket rejectingServer() {
        try {
            var server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            var thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (var ignored = server.accept()) { }
                    catch (IOException exception) { if (!server.isClosed()) throw new IllegalStateException(exception); }
                }
            }, "test-mysql-rejector");
            thread.setDaemon(true);
            thread.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + REJECTOR.getLocalPort() + "/bootstrap_test?connectTimeout=1000&socketTimeout=1000");
        registry.add("spring.datasource.username", () -> "bootstrap_user");
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Value("${local.server.port}") private int port;

    @AfterAll
    static void closeOwnedServer() throws IOException { REJECTOR.close(); }

    @Test
    void unavailableDatabaseReturns503AndDoesNotLeakDetails() throws Exception {
        var response = HttpTestSupport.get(port, "/actuator/health");
        assertEquals(503, response.statusCode());
        var body = HttpTestSupport.json(response);
        assertEquals("DOWN", body.get("status").asText());
        assertFalse(body.has("code"));
        assertFalse(body.has("details"));
        assertFalse(body.has("components"));
        assertThat(response.body()).doesNotContain("jdbc:", PASSWORD, "Exception");
    }
}
