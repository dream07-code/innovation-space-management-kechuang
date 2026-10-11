package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(io.github.dream07code.testsupport.OwnedDataSourceConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmptyDevApiDocumentationIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void dataSource(DynamicPropertyRegistry registry) { MysqlTestSupport.registerMigratedDataSource(registry, MYSQL); }
    @Value("${local.server.port}") private int port;
    @Test void unimplementedProductionApiStillProducesValidEmptyDocumentation() throws Exception {
        var response = HttpTestSupport.get(port, "/v3/api-docs/innovation-space");
        assertEquals(200, response.statusCode());
        var api = HttpTestSupport.json(response);
        assertEquals("v1", api.at("/info/version").asText());
        assertTrue(api.path("paths").isObject());
        assertTrue(api.path("paths").has("/api/v1/auth/login"));
        assertTrue(api.path("paths").has("/api/v1/auth/me"));
        assertEquals(404, HttpTestSupport.get(port, "/api/v1/test-probe/ping").statusCode());
    }
}
