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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class ImplementedApiContractIT {
 @Container static final MySQLContainer MYSQL=MysqlTestSupport.newMysqlContainer();
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){MysqlTestSupport.registerMigratedDataSource(r,MYSQL);}
 @Value("${local.server.port}")int port;
 @Test void generatedOpenApiUsesApprovedBusinessSchemasAndOnlyImplementedRoutes()throws Exception{
  var api=HttpTestSupport.json(HttpTestSupport.get(port,"/v3/api-docs/innovation-space"));
  assertTrue(api.path("paths").has("/api/v1/auth/login"));assertFalse(api.path("paths").has("/api/v1/members/{memberId}/remove"));assertFalse(api.path("paths").has("/api/v1/daily-reports"));
  assertEquals("#/components/schemas/LoginRequest",api.at("/paths/~1api~1v1~1auth~1login/post/requestBody/content/application~1json/schema/$ref").asText());
  assertEquals("string",api.at("/components/schemas/Identity/properties/userId/type").asText());
  assertEquals("implemented",api.at("/paths/~1api~1v1~1auth~1login/post/x-implementation-status").asText());
  assertEquals("#/components/schemas/MemberResponse",api.at("/paths/~1api~1v1~1members/post/responses/201/content/application~1json/schema/$ref").asText());
 }
}
