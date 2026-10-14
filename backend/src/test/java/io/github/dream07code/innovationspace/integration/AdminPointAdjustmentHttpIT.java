package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.ContractAssertions;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class AdminPointAdjustmentHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String studentToken;
  String leaderToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "report_scoring_task", "report_version", "report_review", "report", "api_idempotency", "leader_direction_scope", "auth_session", "audit_log", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", "ACTIVE");
    seedMember(11, "DIRECTION_LEADER", "ACTIVE");
    seedMember(12, "STUDENT", "REMOVED");
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(10).accessToken();
    leaderToken = sessions.issue(11).accessToken();
  }

  @Test
  void adminAddsPositivePointsWithoutReasonVersionOrIdempotency() throws Exception {
    var first = send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":3}");
    assertEquals(200, first.statusCode());
    ContractAssertions.response("POST", "/api/v1/members/10/points", first);
    assertEquals(3, HttpTestSupport.json(first).at("/data/totalPoints").asInt());
    var second = send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":2}");
    assertEquals(200, second.statusCode());
    assertEquals(5, HttpTestSupport.json(second).at("/data/totalPoints").asInt());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id=10 AND source='MANUAL_ADJUSTMENT'", Integer.class));
    assertEquals(5, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=10", Integer.class));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='POINTS_ADDED' AND resource_id=10", Integer.class));
  }

  @Test
  void pointAdditionIsAdminOnlyAndOnlyValidatesPositiveStudentAmount() throws Exception {
    assertEquals(403, send(studentToken, "POST", "/api/v1/members/10/points", "{\"points\":3}").statusCode());
    assertEquals(403, send(leaderToken, "POST", "/api/v1/members/10/points", "{\"points\":3}").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":0}").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":-1}").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":1.5}").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/points", "{\"points\":3,\"reason\":\"not-required\"}").statusCode());
    assertEquals(404, send(adminToken, "POST", "/api/v1/members/999/points", "{\"points\":3}").statusCode());
    assertEquals(409, send(adminToken, "POST", "/api/v1/members/11/points", "{\"points\":3}").statusCode());

    var removedStudent = send(adminToken, "POST", "/api/v1/members/12/points", "{\"points\":4}");
    assertEquals(200, removedStudent.statusCode());
    assertEquals(4, HttpTestSupport.json(removedStudent).at("/data/totalPoints").asInt());
  }

  private void seedMember(int id, String role, String membershipStatus) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, "成员" + id, membershipStatus);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json");
      return client.send(
          request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
