package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@org.springframework.context.annotation.Import(io.github.dream07code.testsupport.OwnedDataSourceConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthEndpointIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        MysqlTestSupport.registerDataSource(registry, MYSQL);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void healthyDatabaseProducesNativeHealthWithoutDetails() throws Exception {
        var response = HttpTestSupport.get(port, "/actuator/health");
        assertEquals(200, response.statusCode());
        var body = HttpTestSupport.json(response);
        assertEquals("UP", body.get("status").asText());
        assertFalse(body.has("code"));
        assertFalse(body.has("components"));
        assertFalse(body.has("details"));
        assertThat(response.body()).doesNotContain("jdbc:", MYSQL.getPassword());
    }

    @Test
    void applicationDoesNotCreateBusinessTablesOnStartup() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
    }

    @Test
    void managementAndUnimplementedBusinessEndpointsAreNotPublished() throws Exception {
        for (var path : new String[]{"/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/configprops", "/api/v1/register", "/api/v1/members"}) {
            assertEquals(404, HttpTestSupport.get(port, path).statusCode(), path);
        }
    }
}
