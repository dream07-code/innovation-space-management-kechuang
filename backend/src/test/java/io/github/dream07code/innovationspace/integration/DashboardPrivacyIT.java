package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import io.swagger.v3.core.util.Yaml;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.auth.initial-password=synthetic-initial")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class DashboardPrivacyIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String leaderToken;
  String studentToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {
        "assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_judge_result",
        "assessment_submission_code","assessment_submission","assessment_problem_link","assessment_problem","assessment",
        "point_ledger","report_review","report_scoring_task","report_version","report",
        "leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(20, "DIRECTION_LEADER", 1);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(30,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(20,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(30).accessToken();
    leaderToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(10).accessToken();
  }

  @Test
  void dashboardResponsesAreRoleScopedReadOnlyAndPrivacySafe() throws Exception {
    int pointsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class);
    var admin = send(adminToken, "GET", "/api/v1/dashboard/admin", null);
    var leader = send(leaderToken, "GET", "/api/v1/dashboard/leader", null);
    var student = send(studentToken, "GET", "/api/v1/dashboard/student", null);
    assertEquals(200, admin.statusCode());
    assertEquals(200, leader.statusCode());
    assertEquals(200, student.statusCode());
    assertNoForbiddenKeys(HttpTestSupport.json(admin).at("/data"));
    assertNoForbiddenKeys(HttpTestSupport.json(leader).at("/data"));
    assertNoForbiddenKeys(HttpTestSupport.json(student).at("/data"));
    assertEquals(pointsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(403, send(studentToken, "GET", "/api/v1/dashboard/admin", null).statusCode());
    assertEquals(403, send(studentToken, "GET", "/api/v1/dashboard/leader", null).statusCode());
    assertEquals(403, send(leaderToken, "GET", "/api/v1/dashboard/student", null).statusCode());
  }

  @Test
  void dashboardContractIsImplementedAndCopiesMatch() throws Exception {
    OpenAPI api;
    try (var stream = getClass().getResourceAsStream("/api-contract.yaml")) {
      api = Yaml.mapper().readValue(stream, OpenAPI.class);
    }
    for (String path : new String[] {
        "/api/v1/dashboard/admin", "/api/v1/dashboard/leader", "/api/v1/dashboard/student"}) {
      assertEquals("implemented", api.getPaths().get(path).getGet().getExtensions().get("x-implementation-status"));
    }
    var cwd = Path.of("").toAbsolutePath();
    var root = cwd.endsWith("backend") ? cwd.getParent() : cwd;
    assertEquals(-1, Files.mismatch(
        root.resolve("docs/api/openapi.yaml"), root.resolve("backend/src/main/resources/api-contract.yaml")));
  }

  private void assertNoForbiddenKeys(JsonNode node) {
    var forbidden = Set.of(
        "studentNo", "contact", "content", "code", "codeText", "codeLocator",
        "privateTestData", "rawProviderResponse", "apiToken", "password");
    var seen = new LinkedHashSet<String>();
    collectKeys(node, seen);
    for (var key : seen) assertFalse(forbidden.contains(key), key);
  }

  private void collectKeys(JsonNode node, Set<String> keys) {
    if (node.isObject()) {
      node.propertyNames().forEach(name -> {
        keys.add(name);
        collectKeys(node.get(name), keys);
      });
    } else if (node.isArray()) {
      node.forEach(value -> collectKeys(value, keys));
    }
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,contact,membership_status,joined_at,membership_started_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,'synthetic-contact','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, directionId, "成员" + id);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
