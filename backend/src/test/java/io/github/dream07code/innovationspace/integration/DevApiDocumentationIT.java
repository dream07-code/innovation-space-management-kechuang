package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.packages-to-scan=io.github.dream07code.testsupport")
@ActiveProfiles("dev")
@Import(WebProbeConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevApiDocumentationIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void dataSource(DynamicPropertyRegistry registry) { MysqlTestSupport.registerDataSource(registry, MYSQL); }
    @Value("${local.server.port}") private int port;

    @Test void knife4jPageAndEveryReferencedResourceAreAccessible() throws Exception {
        var page = HttpTestSupport.get(port, "/doc.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("id=app"));
        for (String path : DocumentationTestSupport.resources(page.body())) {
            var resource = HttpTestSupport.get(port, path);
            assertEquals(200, resource.statusCode(), path);
            assertFalse(resource.body().isBlank(), path);
        }
    }

    @Test void groupedOpenApiDescribesRealTestContractsWithoutWrappingNativeJson() throws Exception {
        var configResponse = HttpTestSupport.get(port, "/v3/api-docs/swagger-config");
        assertEquals(200, configResponse.statusCode());
        var config = HttpTestSupport.json(configResponse);
        assertTrue(config.path("urls").toString().contains("/v3/api-docs/innovation-space"));
        var response = HttpTestSupport.get(port, "/v3/api-docs/innovation-space");
        assertEquals(200, response.statusCode());
        var api = HttpTestSupport.json(response);
        assertTrue(api.path("openapi").asText().startsWith("3.0."));
        assertEquals("学院科创空间管理系统 API", api.at("/info/title").asText());
        assertEquals("v1", api.at("/info/version").asText());
        assertTrue(api.path("paths").has("/api/v1/test-probe/ping"));
        assertFalse(api.path("paths").has("/actuator/health"));
        assertFalse(api.has("code"));
        var schema = api.at("/components/schemas/ProbeRequest");
        assertTrue(schema.path("required").toString().contains("name"));
        assertTrue(schema.at("/properties/password/writeOnly").asBoolean());
        assertTrue(api.at("/paths/~1api~1v1~1test-probe~1validate/post/responses").has("400"));
        assertTrue(api.at("/paths/~1api~1v1~1test-probe~1failure/get/responses").has("500"));
        assertTrue(api.at("/paths/~1api~1v1~1test-probe~1ping/get/responses").has("200"));
    }
}
