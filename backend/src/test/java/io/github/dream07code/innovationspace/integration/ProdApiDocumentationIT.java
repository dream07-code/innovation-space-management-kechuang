package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProdApiDocumentationIT {
    @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void dataSource(DynamicPropertyRegistry registry) { MysqlTestSupport.registerDataSource(registry, MYSQL); }
    @Value("${local.server.port}") private int port;
    @Test void productionProfileDoesNotExposeDocumentationOrItsRealStaticAssets() throws Exception {
        DocumentationTestSupport.assertDocumentationDisabled(port);
    }
}
