package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.PasswordService;
import io.github.dream07code.innovationspace.security.SessionService;
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
    properties = {"app.report.points-policy-status=READY", "app.auth.initial-password=synthetic-initial"})
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class PointsRankingHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordService passwords;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String studentToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "leader_direction_scope", "auth_session", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    insertMember(10, "MOCK010", 1, "甲同学", "ACTIVE");
    insertMember(11, "MOCK011", 1, "乙同学", "ACTIVE");
    insertMember(12, "MOCK012", 2, "丙同学", "ACTIVE");
    insertMember(13, "MOCK013", 1, "移出同学", "REMOVED");
    insertLedger(10, 3, "2026-10-01 01:00:00", "A1");
    insertLedger(10, 2, "2026-09-30 15:59:59", "A2");
    insertLedger(11, 5, "2026-10-02 01:00:00", "B1");
    insertLedger(12, 1, "2026-10-03 01:00:00", "C1");
    insertLedger(13, 20, "2026-10-03 01:00:00", "D1");
    studentToken = sessions.issue(10).accessToken();
  }

  @Test
  void rankingsUseLedgerNetCompetitionRankAndExposeOnlyMinimalFields() throws Exception {
    var overall = send(studentToken, "GET", "/api/v1/rankings/overall?page=1&size=20", null);
    assertEquals(200, overall.statusCode());
    var data = HttpTestSupport.json(overall).path("data");
    assertEquals(3, data.path("total").asInt());
    assertEquals(1, data.at("/items/0/rank").asInt());
    assertEquals(1, data.at("/items/1/rank").asInt());
    assertEquals(3, data.at("/items/2/rank").asInt());
    assertEquals("AI 全栈", data.at("/items/0/directionName").asText());
    assertFalse(overall.body().contains("MOCK010"));
    assertFalse(overall.body().contains("contact"));
    assertFalse(overall.body().contains("studentNo"));

    var direction = send(studentToken, "GET", "/api/v1/rankings/directions/1?page=1&size=1", null);
    assertEquals(200, direction.statusCode());
    assertEquals(2, HttpTestSupport.json(direction).at("/data/total").asInt());
    assertEquals(1, HttpTestSupport.json(direction).at("/data/items/0/rank").asInt());

    var mine = send(studentToken, "GET", "/api/v1/rankings/me?period=MONTH&month=2026-10", null);
    assertEquals(200, mine.statusCode());
    assertEquals(2, HttpTestSupport.json(mine).at("/data/overallRank").asInt());
    assertEquals(2, HttpTestSupport.json(mine).at("/data/directionRank").asInt());
    assertEquals(3, HttpTestSupport.json(mine).at("/data/points").asInt());
    assertEquals(3, HttpTestSupport.json(mine).at("/data/overallCount").asInt());
    assertTrue(HttpTestSupport.json(mine).at("/data/eligible").asBoolean());
  }

  @Test
  void pointsSummaryLedgerFiltersAndScopeAreEnforced() throws Exception {
    var summary = send(studentToken, "GET", "/api/v1/points/me?month=2026-10", null);
    assertEquals(200, summary.statusCode());
    assertEquals(5, HttpTestSupport.json(summary).at("/data/totalPoints").asInt());
    assertEquals(3, HttpTestSupport.json(summary).at("/data/monthlyPoints").asInt());

    var ledger = send(studentToken, "GET", "/api/v1/points/ledger?month=2026-10&page=1&size=20", null);
    assertEquals(200, ledger.statusCode());
    assertEquals(1, HttpTestSupport.json(ledger).at("/data/total").asInt());
    assertEquals("SUBMISSION_BASE", HttpTestSupport.json(ledger).at("/data/items/0/source").asText());
    assertFalse(ledger.body().contains("event_key"));
    assertEquals(403, send(studentToken, "GET", "/api/v1/points/ledger?memberId=11", null).statusCode());
    assertEquals(400, send(studentToken, "GET", "/api/v1/rankings/overall?period=MONTH", null).statusCode());
  }

  private void insertMember(int id, String studentNo, int directionId, String name, String status) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, studentNo, directionId, name, status);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?,?,'STUDENT',false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, studentNo, passwords.encode("synthetic-password12"), name);
  }

  private void insertLedger(int memberId, int delta, String effectiveAt, String eventKey) {
    jdbc.update(
        "INSERT INTO point_ledger(member_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(?,?,'SUBMISSION_BASE',? ,?,'v1',?)",
        memberId, delta, effectiveAt, effectiveAt, eventKey);
  }

  private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      return client.send(
          builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

}
