package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.PasswordService;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.ContractAssertions;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
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
      "app.report.points-policy-status=READY",
      "app.report.points-mode=FINAL_SCORE",
      "app.report.daily-base-points=1",
      "app.auth.initial-password=synthetic-initial",
      "app.ai.worker-scheduling-enabled=false"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class ReportHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordService passwords;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String token;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "report_scoring_task", "report_version", "report_review", "report", "api_idempotency", "leader_direction_scope", "auth_session", "audit_log", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    insertMember(10, "MOCK001", 1, "报告同学", "STUDENT");
    token = sessions.issue(10).accessToken();
  }

  @Test
  void dailyDraftSubmitIsUniqueAndSubmitIsIdempotent() throws Exception {
    String body = "{\"learningContent\":\"learned\"}";
    var draft = send(token, "POST", "/api/v1/daily-reports", body, "11111111-1111-4111-8111-111111111111");
    assertEquals(201, draft.statusCode());
    ContractAssertions.response("POST", "/api/v1/daily-reports", draft);
    String id = HttpTestSupport.json(draft).path("data").path("id").asText();
    var replay = send(token, "POST", "/api/v1/daily-reports", body, "11111111-1111-4111-8111-111111111111");
    assertEquals(201, replay.statusCode());
    assertEquals(id, HttpTestSupport.json(replay).path("data").path("id").asText());
    var duplicate = send(token, "POST", "/api/v1/daily-reports", body, "11111111-1111-4111-8111-111111111112");
    assertEquals(409, duplicate.statusCode());
    assertEquals("REPORT_ALREADY_EXISTS", HttpTestSupport.json(duplicate).at("/code").asText());
    assertEquals(400, send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":1}", null).statusCode());
    var submitted = send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":1}", "22222222-2222-4222-8222-222222222222");
    assertEquals(200, submitted.statusCode());
    ContractAssertions.response("POST", "/api/v1/daily-reports/" + id + "/submit", submitted);
    var submitReplay = send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":1}", "22222222-2222-4222-8222-222222222222");
    assertEquals(200, submitReplay.statusCode());
    assertEquals(HttpTestSupport.json(submitted), HttpTestSupport.json(submitReplay));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task", Integer.class));
  }

  @Test
  void createAndSubmitValidateContractAndUnregisteredRoutesStayAbsent() throws Exception {
    assertEquals(400, send(token, "POST", "/api/v1/daily-reports", "{\"gains\":\"y\"}", "33333333-3333-4333-8333-333333333333").statusCode());
    assertEquals(400, send(token, "POST", "/api/v1/daily-reports", "{\"reportDate\":\"2000-01-01\",\"learningContent\":\"x\",\"gains\":\"y\"}", "33333333-3333-4333-8333-333333333334").statusCode());
    assertEquals(400, send(token, "POST", "/api/v1/weekly-reports", "{\"summary\":\"x\",\"achievements\":\"y\"}", "33333333-3333-4333-8333-333333333335").statusCode());
    var draft = send(token, "POST", "/api/v1/daily-reports", "{\"reportDate\":\"" + today() + "\",\"unknown\":\"x\"}", "33333333-3333-4333-8333-333333333336");
    assertEquals(400, draft.statusCode());
    String id = "999999";
    assertEquals(404, send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":1}", "33333333-3333-4333-8333-333333333338").statusCode());
    assertEquals(404, send(token, "GET", "/api/v1/monthly-assessments", null, null).statusCode());
    assertEquals(404, send(token, "POST", "/api/v1/daily-reports/" + id + "/delete", null, null).statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task", Integer.class));
  }

  @Test
  void patchMergesVersionsAndInvalidSubmittedEditChangesNothing() throws Exception {
    var created = send(token, "POST", "/api/v1/daily-reports", "{\"reportDate\":\"" + today() + "\",\"learningContent\":\"first\",\"gains\":\"initial\"}", "44444444-4444-4444-8444-444444444441");
    assertEquals(201, created.statusCode());
    ContractAssertions.response("POST", "/api/v1/daily-reports", created);
    String id = HttpTestSupport.json(created).path("data").path("id").asText();
    assertEquals(400, send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":1}", null).statusCode());
    assertEquals(400, send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":1,\"reportDate\":\"" + today() + "\"}", null).statusCode());
    var patched = send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":1,\"learningContent\":\"updated\"}", null);
    assertEquals(200, patched.statusCode());
    ContractAssertions.response("PATCH", "/api/v1/daily-reports/" + id, patched);
    assertEquals(2, HttpTestSupport.json(patched).at("/data/version").asInt());
    assertEquals("updated", HttpTestSupport.json(patched).at("/data/learningContent").asText());
    assertEquals("initial", HttpTestSupport.json(patched).at("/data/gains").asText());
    assertEquals(409, send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":1,\"learningContent\":\"stale\"}", null).statusCode());
    var submitted = send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":2}", "44444444-4444-4444-8444-444444444442");
    assertEquals(200, submitted.statusCode());
    assertEquals(400, send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":2,\"learningContent\":\"\"}", null).statusCode());
    assertEquals(2, HttpTestSupport.json(send(token, "GET", "/api/v1/daily-reports/" + id, null, null)).at("/data/version").asInt());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task", Integer.class));
    var valid = send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":2,\"learningContent\":\"changed\",\"gains\":\"better\"}", null);
    assertEquals(200, valid.statusCode());
    assertEquals(3, HttpTestSupport.json(valid).at("/data/version").asInt());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task", Integer.class));
  }

  @Test
  void reportListsApplyObjectScopeBeforePagination() throws Exception {
    insertMember(11, "MOCK002", 1, "同学乙", "STUDENT");
    insertMember(12, "MOCK003", 1, "负责人", "DIRECTION_LEADER");
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin',?,'COLLEGE_ADMIN',false,'ENABLED','学院管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", passwords.encode("synthetic-password12"));
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(12,1,UTC_TIMESTAMP(6))");
    var mine = send(token, "POST", "/api/v1/daily-reports", "{\"reportDate\":\"" + today() + "\",\"learningContent\":\"private draft\",\"gains\":\"mine\"}", "55555555-5555-4555-8555-555555555551");
    assertEquals(201, mine.statusCode());
    jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,first_submitted_at,created_at,updated_at) VALUES(101,11,1,'DAILY',CURDATE(),'SUBMITTED',1,'NOT_REQUESTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(101,101,1,'{\"learningContent\":\"visible\",\"gains\":\"done\"}',UNHEX(SHA2('x',256)),11,UTC_TIMESTAMP(6))");
    var studentList = send(token, "GET", "/api/v1/daily-reports?page=1&size=20", null, null);
    assertEquals(200, studentList.statusCode());
    ContractAssertions.response("GET", "/api/v1/daily-reports", studentList);
    assertEquals(1, HttpTestSupport.json(studentList).at("/data/total").asInt());
    assertEquals(403, send(token, "GET", "/api/v1/daily-reports?memberId=11", null, null).statusCode());
    var adminList = send(sessions.issue(20).accessToken(), "GET", "/api/v1/daily-reports?page=1&size=20", null, null);
    assertEquals(200, adminList.statusCode());
    assertEquals("101", HttpTestSupport.json(adminList).at("/data/items/0/id").asText());
    var leaderList = send(sessions.issue(12).accessToken(), "GET", "/api/v1/daily-reports?page=1&size=20", null, null);
    assertEquals(200, leaderList.statusCode());
    assertEquals(1, HttpTestSupport.json(leaderList).at("/data/total").asInt());
    assertFalse(leaderList.body().contains("private draft"));
  }

  @Test
  void weeklyDraftPatchKeepsComputedWeekAndMergesContent() throws Exception {
    var created = send(token, "POST", "/api/v1/weekly-reports", "{\"weekStart\":\"" + weekStart() + "\",\"summary\":\"first week\",\"achievements\":\"done\"}", "66666666-6666-4666-8666-666666666661");
    assertEquals(201, created.statusCode());
    ContractAssertions.response("POST", "/api/v1/weekly-reports", created);
    String id = HttpTestSupport.json(created).path("data").path("id").asText();
    var patched = send(token, "PATCH", "/api/v1/weekly-reports/" + id, "{\"version\":1,\"summary\":\"updated week\"}", null);
    assertEquals(200, patched.statusCode());
    ContractAssertions.response("PATCH", "/api/v1/weekly-reports/" + id, patched);
    assertEquals("updated week", HttpTestSupport.json(patched).at("/data/summary").asText());
    assertEquals("done", HttpTestSupport.json(patched).at("/data/achievements").asText());
    assertFalse(HttpTestSupport.json(patched).at("/data/weekEnd").asText().isBlank());
  }

  @Test
  void lateDraftCannotBeEditedOrSubmitted() throws Exception {
    var created = send(token, "POST", "/api/v1/daily-reports", "{\"reportDate\":\"" + today() + "\",\"learningContent\":\"late\",\"gains\":\"draft\"}", "77777777-7777-4777-8777-777777777771");
    String id = HttpTestSupport.json(created).path("data").path("id").asText();
    jdbc.update("UPDATE report SET report_date=DATE_SUB(CURDATE(),INTERVAL 1 DAY) WHERE id=?", Long.valueOf(id));
    assertEquals(409, send(token, "PATCH", "/api/v1/daily-reports/" + id, "{\"version\":1,\"learningContent\":\"late edit\"}", null).statusCode());
    assertEquals(409, send(token, "POST", "/api/v1/daily-reports/" + id + "/submit", "{\"version\":1}", "77777777-7777-4777-8777-777777777772").statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM report_scoring_task", Integer.class));
  }

  @Test
  void staffCanManuallyScoreSubmittedReportWithinScopeOnly() throws Exception {
    var created =
        send(
            token,
            "POST",
            "/api/v1/daily-reports",
            "{\"reportDate\":\"" + today() + "\",\"learningContent\":\"manual score\"}",
            "88888888-8888-4888-8888-888888888881");
    assertEquals(201, created.statusCode());
    String id = HttpTestSupport.json(created).path("data").path("id").asText();
    assertEquals(
        200,
        send(
                token,
                "POST",
                "/api/v1/daily-reports/" + id + "/submit",
                "{\"version\":1}",
                "88888888-8888-4888-8888-888888888882")
            .statusCode());

    // 学生本人不能给自己评分。
    assertEquals(
        403,
        send(token, "POST", "/api/v1/daily-reports/" + id + "/score", "{\"score\":2}", null)
            .statusCode());

    // 其他方向的负责人越权评分被拒绝。
    insertMember(12, "MOCK012", 2, "二方向负责人", "DIRECTION_LEADER");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(12,2,UTC_TIMESTAMP(6))");
    String otherLeader = sessions.issue(12).accessToken();
    assertEquals(
        403,
        send(otherLeader, "POST", "/api/v1/daily-reports/" + id + "/score", "{\"score\":2}", null)
            .statusCode());

    // 学院管理员可评任意方向。管理员是无成员身份的独立账号（ck_user_identity）。
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(11,NULL,'MOCK011',?,'COLLEGE_ADMIN',false,'ENABLED','学院管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        passwords.encode("synthetic-password12"));
    String admin = sessions.issue(11).accessToken();
    var scored =
        send(
            admin,
            "POST",
            "/api/v1/daily-reports/" + id + "/score",
            "{\"score\":2,\"reason\":\"内容较完整\"}",
            null);
    assertEquals(200, scored.statusCode());
    ContractAssertions.response("POST", "/api/v1/daily-reports/" + id + "/score", scored);
    var body = HttpTestSupport.json(scored).path("data");
    assertEquals(2, body.path("finalScore").asInt());
    assertEquals("MANUAL", body.path("scoreStatus").asText());
    assertEquals("内容较完整", body.path("scoreReason").asText());
    assertEquals("报告同学", body.path("memberName").asText());
    assertEquals(2, jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?",
        Integer.class, Long.valueOf(id)));

    // 重新评分只追加差额流水，不重复累计。
    var rescored =
        send(
            admin,
            "POST",
            "/api/v1/daily-reports/" + id + "/score",
            "{\"score\":3}",
            null);
    assertEquals(200, rescored.statusCode());
    assertEquals(3, jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?",
        Integer.class, Long.valueOf(id)));
    assertEquals(3, HttpTestSupport.json(rescored).path("data").path("finalScore").asInt());
    // 两次成功评分（2 分、改 3 分）各记一条审计；被拒的 4 分不落审计。
    assertEquals(2, jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE action='REPORT_MANUAL_SCORE'", Integer.class));

    // 超出 1–3 分范围被拒绝，评分与积分保持不变。
    assertEquals(
        400,
        send(admin, "POST", "/api/v1/daily-reports/" + id + "/score", "{\"score\":4}", null)
            .statusCode());
    assertEquals(3, jdbc.queryForObject(
        "SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=?",
        Integer.class, Long.valueOf(id)));
  }

  private void insertMember(int id, String studentNo, int directionId, String name, String role) {
    jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, studentNo, directionId, name);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?,?,'" + role + "',false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, id, studentNo, passwords.encode("synthetic-password12"), name);
  }

  private HttpResponse<String> send(String auth, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + auth);
      if (body != null) request.header("Content-Type", "application/json");
      if (key != null) request.header("Idempotency-Key", key);
      return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String today() {
    return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
  }

  private static String weekStart() {
    return LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY).toString();
  }
}
