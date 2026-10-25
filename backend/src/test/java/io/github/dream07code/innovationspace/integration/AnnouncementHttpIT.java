package io.github.dream07code.innovationspace.integration;

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
    properties = {
      "app.membership.auto-removal-enabled=false",
      "app.auth.initial-password=synthetic-initial"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AnnouncementHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String studentOneToken;
  String studentTwoToken;
  String leaderOneToken;
  String leaderTwoToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table :
        new String[] {
          "announcement",
          "point_ledger",
          "api_idempotency",
          "leader_direction_scope",
          "auth_session",
          "audit_log",
          "app_user",
          "member"
        }) {
      jdbc.update("DELETE FROM " + table);
    }
    // 方向 1（AI 全栈）、方向 2（鸿蒙）来自 V2 种子。
    seedMember(10, "STUDENT", 1);
    seedMember(11, "STUDENT", 2);
    seedMember(13, "DIRECTION_LEADER", 1);
    seedMember(14, "DIRECTION_LEADER", 2);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(13,1,UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(14,2,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    studentOneToken = sessions.issue(10).accessToken();
    studentTwoToken = sessions.issue(11).accessToken();
    leaderOneToken = sessions.issue(13).accessToken();
    leaderTwoToken = sessions.issue(14).accessToken();
  }

  @Test
  void adminPublishesCollegeAndDirectionAnnouncementsWithScopedVisibility() throws Exception {
    var college =
        send(adminToken, "POST", "/api/v1/announcements",
            "{\"title\":\"学院通知\",\"content\":\"全体停课一天\"}",
            "11111111-1111-4111-8111-111111111111");
    assertEquals(201, college.statusCode());
    var collegeData = HttpTestSupport.json(college).path("data");
    assertEquals("COLLEGE", collegeData.path("scope").asText());
    assertTrue(collegeData.path("directionId").isNull());

    var directed =
        send(adminToken, "POST", "/api/v1/announcements",
            "{\"title\":\"方向通知\",\"content\":\"方向一今晚答辩\",\"directionId\":\"1\"}",
            "22222222-2222-4222-8222-222222222222");
    assertEquals(201, directed.statusCode());
    assertEquals("DIRECTION", HttpTestSupport.json(directed).path("data").path("scope").asText());
    assertEquals("1", HttpTestSupport.json(directed).path("data").path("directionId").asText());

    // 学生1（方向1）：学院级 + 方向1 都可见；总条数 2。
    var visibleOne = send(studentOneToken, "GET", "/api/v1/announcements", "", null);
    assertEquals(200, visibleOne.statusCode());
    assertEquals(2, HttpTestSupport.json(visibleOne).at("/data/total").asInt());
    // 学生2（方向2）：只看学院级。
    var visibleTwo = send(studentTwoToken, "GET", "/api/v1/announcements", "", null);
    assertEquals(1, HttpTestSupport.json(visibleTwo).at("/data/total").asInt());
    assertEquals("学院通知", HttpTestSupport.json(visibleTwo).at("/data/items/0/title").asText());
    // 管理员全可见。
    assertEquals(
        2,
        HttpTestSupport.json(send(adminToken, "GET", "/api/v1/announcements", "", null))
            .at("/data/total")
            .asInt());
  }

  @Test
  void leaderPublishesOwnDirectionOnlyAndOthersCannotReadIt() throws Exception {
    assertEquals(
        201,
        send(leaderOneToken, "POST", "/api/v1/announcements",
                "{\"title\":\"方向一通知\",\"content\":\"本周打卡\",\"directionId\":\"1\"}",
                "33333333-3333-4333-8333-333333333331")
            .statusCode());
    // 越权方向：403。
    assertEquals(
        403,
        send(leaderOneToken, "POST", "/api/v1/announcements",
                "{\"title\":\"越权\",\"content\":\"x\",\"directionId\":\"2\"}",
                "33333333-3333-4333-8333-333333333332")
            .statusCode());
    // 负责人不带 directionId（不具备学院级资格）：400。
    assertEquals(
        400,
        send(leaderOneToken, "POST", "/api/v1/announcements",
                "{\"title\":\"缺方向\",\"content\":\"x\"}",
                "33333333-3333-4333-8333-333333333333")
            .statusCode());

    // 其他方向负责人与学生2 读不到方向1公告。
    assertEquals(
        0,
        HttpTestSupport.json(send(leaderTwoToken, "GET", "/api/v1/announcements", "", null))
            .at("/data/total")
            .asInt());
    assertEquals(
        0,
        HttpTestSupport.json(send(studentTwoToken, "GET", "/api/v1/announcements", "", null))
            .at("/data/total")
            .asInt());
  }

  @Test
  void withdrawIsIdempotentPermissionCheckedAndHidesFromLists() throws Exception {
    var created =
        send(adminToken, "POST", "/api/v1/announcements",
            "{\"title\":\"将被撤回\",\"content\":\"x\",\"directionId\":\"1\"}",
            "44444444-4444-4444-8444-444444444441");
    String id = HttpTestSupport.json(created).path("data").path("id").asText();

    // 非创建者不能撤回：负责人对该公告 403。
    assertEquals(
        403,
        send(leaderOneToken, "POST", "/api/v1/announcements/" + id + "/withdraw", "{}",
                "44444444-4444-4444-8444-444444444442")
            .statusCode());

    var withdrawn =
        send(adminToken, "POST", "/api/v1/announcements/" + id + "/withdraw", "{}",
            "44444444-4444-4444-8444-444444444443");
    assertEquals(200, withdrawn.statusCode());
    var replay =
        send(adminToken, "POST", "/api/v1/announcements/" + id + "/withdraw", "{}",
            "44444444-4444-4444-8444-444444444443");
    assertEquals(200, replay.statusCode());
    assertTrue(HttpTestSupport.json(withdrawn).equals(HttpTestSupport.json(replay)));

    // 撤回后所有视角不可见；审计留痕。
    assertEquals(
        0,
        HttpTestSupport.json(send(studentOneToken, "GET", "/api/v1/announcements", "", null))
            .at("/data/total")
            .asInt());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action='ANNOUNCEMENT_WITHDRAWN' AND resource_id="
                + id,
            Integer.class));

    // 负责人撤自己发的方向公告：允许；其他方向负责人对它 404（不可枚举）。
    var own =
        send(leaderOneToken, "POST", "/api/v1/announcements",
            "{\"title\":\"自撤\",\"content\":\"x\",\"directionId\":\"1\"}",
            "44444444-4444-4444-8444-444444444444");
    String ownId = HttpTestSupport.json(own).path("data").path("id").asText();
    assertEquals(
        200,
        send(leaderOneToken, "POST", "/api/v1/announcements/" + ownId + "/withdraw", "{}",
                "44444444-4444-4444-8444-444444444445")
            .statusCode());
    assertEquals(
        404,
        send(leaderTwoToken, "POST", "/api/v1/announcements/" + ownId + "/withdraw", "{}",
                "44444444-4444-4444-8444-444444444446")
            .statusCode());
  }

  @Test
  void validationAndRoleGatesAreEnforced() throws Exception {
    assertEquals(
        403,
        send(studentOneToken, "POST", "/api/v1/announcements",
                "{\"title\":\"学生\",\"content\":\"x\"}",
                "55555555-5555-4555-8555-555555555551")
            .statusCode());
    assertEquals(
        400,
        send(adminToken, "POST", "/api/v1/announcements", "{\"title\":\"\",\"content\":\"x\"}",
                "55555555-5555-4555-8555-555555555552")
            .statusCode());
    assertEquals(
        400,
        send(adminToken, "POST", "/api/v1/announcements",
                "{\"title\":\"" + "长".repeat(121) + "\",\"content\":\"x\"}",
                "55555555-5555-4555-8555-555555555553")
            .statusCode());
    assertEquals(
        400,
        send(adminToken, "POST", "/api/v1/announcements",
                "{\"title\":\"t\",\"content\":\"" + "长".repeat(2001) + "\"}",
                "55555555-5555-4555-8555-555555555554")
            .statusCode());
    assertEquals(
        404,
        send(adminToken, "POST", "/api/v1/announcements",
                "{\"title\":\"t\",\"content\":\"x\",\"directionId\":\"999\"}",
                "55555555-5555-4555-8555-555555555555")
            .statusCode());
    // 未登录 401。
    assertEquals(401, send("", "GET", "/api/v1/announcements", "", null).statusCode());
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, directionId, "成员" + id);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> send(
      String token, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var builder =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Content-Type", "application/json");
      if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
      if (key != null) builder.header("Idempotency-Key", key);
      return client.send(
          builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
