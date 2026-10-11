package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.context-path=/space")
@Import({OwnedDataSourceConfiguration.class, WebProbeConfiguration.class})
@Testcontainers
@DirtiesContext
class UnmigratedDatabaseIT {
    @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){MysqlTestSupport.registerDataSource(r,MYSQL);}
    @Value("${local.server.port}") int port;
    @Test void unmigratedDatabaseIsNotReportedAsHealthy() throws Exception {
        var response=HttpTestSupport.get(port,"/space/actuator/health");
        assertEquals(503,response.statusCode());assertEquals("DOWN",HttpTestSupport.json(response).path("status").asText());
        assertFalse(response.body().contains("jdbc:"));
        var business=HttpTestSupport.get(port,"/space/api/v1/test-probe/ping");
        assertEquals(503,business.statusCode());
        assertEquals("SERVICE_UNAVAILABLE",HttpTestSupport.json(business).path("code").asText());
        assertEquals(404,HttpTestSupport.get(port,"/space/unknown-path").statusCode());
        try(var connection=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            var statement=connection.createStatement();
            var tables=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            tables.next();assertEquals(0,tables.getInt(1),"default Web startup must not create schema");
        }
    }
}
