package io.github.dream07code.innovationspace.integration;
import io.github.dream07code.testsupport.*;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
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
  assertTrue(api.path("paths").has("/api/v1/auth/login"));assertFalse(api.path("paths").has("/api/v1/members/{memberId}/remove"));
  for (String path : new String[]{"/api/v1/daily-reports","/api/v1/daily-reports/{reportId}","/api/v1/weekly-reports","/api/v1/weekly-reports/{reportId}","/api/v1/points/me","/api/v1/points/ledger","/api/v1/rankings/overall","/api/v1/rankings/directions/{directionId}","/api/v1/rankings/me"}) {
    assertTrue(api.path("paths").has(path), path);
  }
  assertFalse(api.path("paths").has("/api/v1/monthly-assessments"));
  assertFalse(api.path("paths").has("/api/v1/tasks"));
  assertFalse(api.path("paths").has("/api/v1/system/rules/monthly-assessment"));
  assertEquals("implemented",api.at("/paths/~1api~1v1~1members~1{memberId}~1restore/post/x-implementation-status").asText());
  assertFalse(api.path("paths").has("/api/v1/members/{memberId}/remove"));
  assertEquals("#/components/schemas/LoginRequest",api.at("/paths/~1api~1v1~1auth~1login/post/requestBody/content/application~1json/schema/$ref").asText());
  assertEquals("string",api.at("/components/schemas/Identity/properties/userId/type").asText());
  assertEquals("implemented",api.at("/paths/~1api~1v1~1auth~1login/post/x-implementation-status").asText());
  assertEquals("#/components/schemas/MemberResponse",api.at("/paths/~1api~1v1~1members/post/responses/201/content/application~1json/schema/$ref").asText());
  assertEquals("#/components/schemas/DailyReportPageResponse",api.at("/paths/~1api~1v1~1daily-reports/get/responses/200/content/application~1json/schema/$ref").asText());
  assertEquals("#/components/schemas/RankingPageResponse",api.at("/paths/~1api~1v1~1rankings~1overall/get/responses/200/content/application~1json/schema/$ref").asText());
  try (var contractStream = getClass().getResourceAsStream("/api-contract.yaml")) {
    OpenAPI approved = Yaml.mapper().readValue(contractStream, OpenAPI.class);
    assertEquals("implemented", approved.getPaths().get("/api/v1/members/{memberId}/restore").getPost().getExtensions().get("x-implementation-status"));
    assertNull(approved.getPaths().get("/api/v1/monthly-assessments"));
    assertNull(approved.getPaths().get("/api/v1/tasks"));
    assertNull(approved.getPaths().get("/api/v1/system/rules/monthly-assessment"));
  }
  var cwd = java.nio.file.Path.of("").toAbsolutePath();
  var root = cwd.endsWith("backend") ? cwd.getParent() : cwd;
  assertEquals(-1, java.nio.file.Files.mismatch(root.resolve("docs/api/openapi.yaml"), root.resolve("backend/src/main/resources/api-contract.yaml")));
 }
}
