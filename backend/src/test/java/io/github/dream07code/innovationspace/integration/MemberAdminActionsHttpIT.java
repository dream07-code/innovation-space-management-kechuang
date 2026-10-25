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
class MemberAdminActionsHttpIT {
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

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table :
        new String[] {
          "point_ledger", "report_scoring_task", "report_version", "report_review", "report",
          "api_idempotency", "leader_direction_scope", "auth_session", "audit_log", "app_user",
          "member"
        }) {
      jdbc.update("DELETE FROM " + table);
    }
    // 10：有报告和积分流水的学生（删除应被引用保护拦下）。
    seedMember(10, "STUDENT", "ACTIVE");
    jdbc.update(
        "INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,created_at,updated_at) VALUES(100,10,1,'DAILY',CURDATE(),'SUBMITTED',1,'SCORED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO point_ledger(member_id,report_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(10,100,2,'SUBMISSION_BASE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'v1','seed-original')");
    // 11：干净的在籍学生（可删除 / 可重置积分）。
    seedMember(11, "STUDENT", "ACTIVE");
    sessions.issue(11);
    // 12：负责人成员（删除应被角色检查拦下）。
    seedMember(12, "DIRECTION_LEADER", "ACTIVE");
    // 13：已移出且无业务记录的学生（物理删除同样适用）。
    seedMember(13, "STUDENT", "REMOVED");
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(11).accessToken();
  }

  @Test
  void deleteRemovesCleanStudentButProtectsReferencedOnes() throws Exception {
    // 学生与负责人都不能删除。
    assertEquals(
        403,
        send(studentToken, "POST", "/api/v1/members/10/delete",
                "{\"version\":1,\"reason\":\"学生不能删除\"}", "11111111-1111-4111-8111-111111111101")
            .statusCode());
    // 有报告/积分流水的学生：409 MEMBER_IN_USE，数据原样保留。
    var inUse =
        send(adminToken, "POST", "/api/v1/members/10/delete",
            "{\"version\":1,\"reason\":\"误加学生，需要删除\"}", "11111111-1111-4111-8111-111111111102");
    assertEquals(409, inUse.statusCode());
    assertEquals("MEMBER_IN_USE", HttpTestSupport.json(inUse).path("code").asText());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE id=10", Integer.class));

    // 负责人成员：409 INVALID_STATE。
    assertEquals(
        409,
        send(adminToken, "POST", "/api/v1/members/12/delete",
                "{\"version\":1,\"reason\":\"负责人不能删除\"}", "11111111-1111-4111-8111-111111111103")
            .statusCode());

    // 版本冲突：409 VERSION_CONFLICT。
    assertEquals(
        409,
        send(adminToken, "POST", "/api/v1/members/11/delete",
                "{\"version\":9,\"reason\":\"版本过期\"}", "11111111-1111-4111-8111-111111111104")
            .statusCode());

    // 干净学生删除成功：成员/账号/会话一并消失，幂等键与账号清理后审计保留。
    jdbc.update(
        "INSERT INTO api_idempotency(actor_user_id,http_method,normalized_path,idempotency_key,request_digest,response_status,response_body,created_at,expires_at) VALUES(11,'POST','/api/v1/daily-reports','seed-key-11',UNHEX(SHA2('seed',256)),201,'{}',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY))");
    jdbc.update(
        "INSERT INTO audit_log(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at) VALUES(11,'LOGIN','SESSION',NULL,'Login successful','SUCCESS',UTC_TIMESTAMP(6))");
    var deleted =
        send(adminToken, "POST", "/api/v1/members/11/delete",
            "{\"version\":1,\"reason\":\"学号录错，删除重建\"}", "11111111-1111-4111-8111-111111111105");
    assertEquals(200, deleted.statusCode());
    assertTrue(HttpTestSupport.json(deleted).path("data").isNull());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE id=11", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=11", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=11", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE actor_user_id=11", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_user_id IS NULL AND action='LOGIN'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MEMBER_DELETED' AND resource_id=11", Integer.class));
    assertEquals(404, send(adminToken, "GET", "/api/v1/members/11", "", "11111111-1111-4111-8111-111111111106").statusCode());

    // 幂等重放返回同一结果。
    var replay =
        send(adminToken, "POST", "/api/v1/members/11/delete",
            "{\"version\":1,\"reason\":\"学号录错，删除重建\"}", "11111111-1111-4111-8111-111111111105");
    assertEquals(200, replay.statusCode());
    assertTrue(HttpTestSupport.json(deleted).equals(HttpTestSupport.json(replay)));

    // 已移出且无业务记录的学生同样可物理删除。
    var removed =
        send(adminToken, "POST", "/api/v1/members/13/delete",
            "{\"version\":1,\"reason\":\"清理误录学生\"}", "11111111-1111-4111-8111-111111111107");
    assertEquals(200, removed.statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE id=13", Integer.class));
  }

  @Test
  void resetPointsZeroesNetAndKeepsHistory() throws Exception {
    assertEquals(
        403,
        send(studentToken, "POST", "/api/v1/members/11/points/reset",
                "{\"version\":1,\"reason\":\"学生不能重置\"}", "22222222-2222-4222-8222-222222222201")
            .statusCode());
    // 负责人成员 / 已移出学生：409 INVALID_STATE。
    assertEquals(
        409,
        send(adminToken, "POST", "/api/v1/members/12/points/reset",
                "{\"version\":1,\"reason\":\"负责人不能重置\"}", "22222222-2222-4222-8222-222222222202")
            .statusCode());
    assertEquals(
        409,
        send(adminToken, "POST", "/api/v1/members/13/points/reset",
                "{\"version\":1,\"reason\":\"已移出不能重置\"}", "22222222-2222-4222-8222-222222222203")
            .statusCode());
    assertEquals(
        409,
        send(adminToken, "POST", "/api/v1/members/11/points/reset",
                "{\"version\":9,\"reason\":\"版本过期\"}", "22222222-2222-4222-8222-222222222204")
            .statusCode());

    // 加 5 分再重置：净积分归零，流水历史保留，审计留痕。
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/members/11/points", "{\"points\":5}", null).statusCode());
    var reset =
        send(adminToken, "POST", "/api/v1/members/11/points/reset",
            "{\"version\":1,\"reason\":\"积分规则试运行结束，清零重新统计\"}", "22222222-2222-4222-8222-222222222205");
    assertEquals(200, reset.statusCode());
    assertEquals(0, HttpTestSupport.json(reset).path("data").path("totalPoints").asInt());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=11", Integer.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE member_id=11", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE member_id=11 AND source='REVERSAL'"
                + " AND rule_version='ADMIN_POINTS_RESET_V1'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action='POINTS_RESET' AND resource_id=11",
            Integer.class));

    // 幂等重放一致；再次重置不会产生新的冲正（净额仍为 0）。
    var replay =
        send(adminToken, "POST", "/api/v1/members/11/points/reset",
            "{\"version\":1,\"reason\":\"积分规则试运行结束，清零重新统计\"}", "22222222-2222-4222-8222-222222222205");
    assertEquals(200, replay.statusCode());
    assertTrue(HttpTestSupport.json(reset).equals(HttpTestSupport.json(replay)));
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/members/11/points/reset",
                "{\"version\":1,\"reason\":\"再次重置保持为零\"}", "22222222-2222-4222-8222-222222222206")
            .statusCode());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE member_id=11", Integer.class));
  }

  private void seedMember(int id, String role, String membershipStatus) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, "成员" + id, membershipStatus);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> send(
      String token, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var builder =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json");
      if (key != null) builder.header("Idempotency-Key", key);
      return client.send(
          builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
