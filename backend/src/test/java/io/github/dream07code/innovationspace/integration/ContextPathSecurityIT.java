package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.context-path=/space")
@ActiveProfiles("default")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class ContextPathSecurityIT {
    @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
    @Value("${local.server.port}") int port;
    @Test void authenticatedRoutesStayProtectedUnderContextPath()throws Exception {
        for(String path:new String[]{"/directions","/members","/auth/me"})assertEquals(401,HttpTestSupport.get(port,"/space/api/v1"+path).statusCode(),path);
        assertEquals(404,HttpTestSupport.get(port,"/space/api/v1/unknown").statusCode());
    }
}
