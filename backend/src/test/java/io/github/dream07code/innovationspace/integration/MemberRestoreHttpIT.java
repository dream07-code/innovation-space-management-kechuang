package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.membership.MembershipRemovalService;
import io.github.dream07code.innovationspace.points.PointLedgerService;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.ContractAssertions;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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
    properties = {
      "app.membership.auto-removal-enabled=false",
      "app.auth.initial-password=synthetic-initial"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class MemberRestoreHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired PointLedgerService points;
  @Autowired MembershipRemovalService removals;
  @Value("${local.server.port}") int port;
  String adminToken;
  String studentToken;
  String leaderToken;
  Timestamp originalStart;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "report_scoring_task", "report_version", "report_review", "report", "api_idempotency", "leader_direction_scope", "auth_session", "audit_log", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", "ACTIVE");
    originalStart = jdbc.queryForObject("SELECT membership_started_at FROM member WHERE id=10", Timestamp.class);
    sessions.issue(10);
    jdbc.update("UPDATE member SET membership_status='REMOVED',removed_at=UTC_TIMESTAMP(6),removal_reason='TWO_WEEKS_NO_DAILY_REPORT',version=2,updated_at=UTC_TIMESTAMP(6) WHERE id=10");
    jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=10");
    seedReportAndZeroedPoints(10);

    seedMember(11, "STUDENT", "ACTIVE");
    seedMember(12, "DIRECTION_LEADER", "REMOVED");
    seedMember(13, "DIRECTION_LEADER", "ACTIVE");
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(11).accessToken();
    leaderToken = sessions.issue(13).accessToken();
  }

  @Test
  void restoreIsAdminOnlyVersionedAndIdempotent() throws Exception {
    var success =
        send(
            adminToken,
            "POST",
            "/api/v1/members/10/restore",
            "{\"version\":2,\"reason\":\"已确认学生返组\"}",
            "11111111-1111-4111-8111-111111111111");
    assertEquals(200, success.statusCode());
    var data = HttpTestSupport.json(success).path("data");
    ContractAssertions.response("POST", "/api/v1/members/10/restore", success);
    assertEquals("ACTIVE", data.path("membershipStatus").asText());
    assertTrue(data.path("removedAt").isNull());
    assertTrue(data.path("removalReason").isNull());
    assertEquals("DISABLED", data.path("accountStatus").asText());
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=10", Integer.class));
    assertNotEquals(originalStart, jdbc.queryForObject("SELECT membership_started_at FROM member WHERE id=10", Timestamp.class));
    assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE user_id=10", Timestamp.class));
    assertFalse(removals.removeDueMember(10));

    var replay =
        send(
            adminToken,
            "POST",
            "/api/v1/members/10/restore",
            "{\"version\":2,\"reason\":\"已确认学生返组\"}",
            "11111111-1111-4111-8111-111111111111");
    assertEquals(200, replay.statusCode());
    assertEquals(HttpTestSupport.json(success), HttpTestSupport.json(replay));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MEMBER_RESTORED' AND resource_id=10", Integer.class));

    var conflict =
        send(
            adminToken,
            "POST",
            "/api/v1/members/10/restore",
            "{\"version\":3,\"reason\":\"不同请求复用同一幂等键\"}",
            "11111111-1111-4111-8111-111111111111");
    assertEquals(409, conflict.statusCode());
    assertEquals("IDEMPOTENCY_CONFLICT", HttpTestSupport.json(conflict).path("code").asText());
  }

  @Test
  void restoreRejectsWrongRoleStateVersionAndPayload() throws Exception {
    assertEquals(403, send(studentToken, "POST", "/api/v1/members/10/restore", "{\"version\":2,\"reason\":\"学生不能恢复\"}", "22222222-2222-4222-8222-222222222221").statusCode());
    assertEquals(403, send(leaderToken, "POST", "/api/v1/members/10/restore", "{\"version\":2,\"reason\":\"负责人不能恢复\"}", "22222222-2222-4222-8222-222222222222").statusCode());
    assertEquals(409, send(adminToken, "POST", "/api/v1/members/11/restore", "{\"version\":1,\"reason\":\"目标仍在籍\"}", "22222222-2222-4222-8222-222222222223").statusCode());
    assertEquals(409, send(adminToken, "POST", "/api/v1/members/12/restore", "{\"version\":1,\"reason\":\"目标不是学生\"}", "22222222-2222-4222-8222-222222222224").statusCode());
    assertEquals(409, send(adminToken, "POST", "/api/v1/members/10/restore", "{\"version\":9,\"reason\":\"版本已经变化\"}", "22222222-2222-4222-8222-222222222225").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/restore", "{\"version\":2}", "22222222-2222-4222-8222-222222222226").statusCode());
    assertEquals(400, send(adminToken, "POST", "/api/v1/members/10/restore", "{\"version\":2,\"reason\":\"有效原因\",\"extra\":true}", "22222222-2222-4222-8222-222222222227").statusCode());
    assertEquals(2, jdbc.queryForObject("SELECT version FROM member WHERE id=10", Integer.class));
  }

  private void seedMember(int id, String role, String membershipStatus) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, "成员" + id, membershipStatus);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private void seedReportAndZeroedPoints(int memberId) {
    jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,first_submitted_at,created_at,updated_at) VALUES(100,?,1,'DAILY',CURDATE(),'SUBMITTED',1,'SCORED',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 31 DAY),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", memberId);
    jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(100,100,1,'{}',UNHEX(SHA2('{}',256)),?,UTC_TIMESTAMP(6))", memberId);
    jdbc.update("INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(?,100,100,2,'SUBMISSION_BASE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'v1','restore-original')", memberId);
    points.reverseNonZeroEntries(memberId, "INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1", java.time.Instant.now());
  }

  private HttpResponse<String> send(
      String token, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", key);
      return client.send(
          request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
