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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
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
class LeaderDashboardHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String leaderOneToken;
  String leaderTwoToken;
  String studentToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {
        "assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result",
        "assessment_submission_code","assessment_submission","assessment_problem_link","assessment_problem","assessment",
        "point_ledger","report_review","report_scoring_task","report_version","report",
        "leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(11, "STUDENT", 1);
    seedMember(12, "STUDENT", 2);
    seedMember(20, "DIRECTION_LEADER", 1);
    seedMember(21, "DIRECTION_LEADER", 2);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(30,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(20,1,UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(21,2,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(30).accessToken();
    leaderOneToken = sessions.issue(20).accessToken();
    leaderTwoToken = sessions.issue(21).accessToken();
    studentToken = sessions.issue(10).accessToken();
  }

  @Test
  void leaderDashboardOnlyReturnsAuthorizedDirectionAndStudentRows() throws Exception {
    Timestamp now = businessNow();
    long report10 = report(10, 1, "DAILY", now, "SUBMITTED", "SCORED");
    long report11 = report(11, 1, "DAILY", now, "SUBMITTED", "REVIEW_REQUIRED");
    report(12, 2, "DAILY", now, "SUBMITTED", "QUEUED");
    versionAndTask(report11, "FAILED");
    points(10, 3, now);
    points(11, 2, now);
    points(12, 10, now);

    var response = send(leaderOneToken, "GET", "/api/v1/dashboard/leader", null);
    assertEquals(200, response.statusCode());
    ContractAssertions.response("GET", "/api/v1/dashboard/leader", response);
    var data = HttpTestSupport.json(response).at("/data");
    assertEquals("7d", data.at("/range").asText());
    assertEquals(2, data.at("/overview/activeStudents").asInt());
    assertEquals(2, data.at("/students").size());
    assertFalse(response.body().contains("\"memberId\":\"12\""));
    assertFalse(response.body().contains("MOCK12"));
    assertFalse(response.body().contains("studentNo"));
    assertFalse(response.body().contains("contact"));
    assertTrue(data.at("/rankingPreview").size() <= 10);
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE delta<0", Integer.class));
  }

  @Test
  void leaderRankingUsesCompetitionRankForTiedStudents() throws Exception {
    Timestamp now = businessNow();
    seedMember(13, "STUDENT", 1);
    points(10, 3, now);
    points(11, 3, now);
    points(13, 3, now);
    var response = send(leaderOneToken, "GET", "/api/v1/dashboard/leader", null);
    assertEquals(200, response.statusCode());
    var preview = HttpTestSupport.json(response).at("/data/rankingPreview");
    assertEquals(3, preview.size());
    for (var row : preview) assertEquals(1, row.at("/rank").asInt());
  }

  @Test
  void leaderScopeAndRoleMatrixAreEnforced() throws Exception {
    var other = send(leaderTwoToken, "GET", "/api/v1/dashboard/leader", null);
    assertEquals(200, other.statusCode());
    assertTrue(HttpTestSupport.json(other).at("/data/students").size() <= 1);
    assertEquals(403, send(adminToken, "GET", "/api/v1/dashboard/leader", null).statusCode());
    assertEquals(403, send(studentToken, "GET", "/api/v1/dashboard/leader", null).statusCode());
    assertEquals(400, send(leaderOneToken, "GET", "/api/v1/dashboard/leader?range=1d", null).statusCode());
  }

  private long report(
      int memberId,
      int directionId,
      String type,
      Timestamp submittedAt,
      String status,
      String scoreStatus) {
    jdbc.update(
        "INSERT INTO report(member_id,direction_id,report_type,report_date,week_start,week_end,status,current_version,first_submitted_at,score_status,final_score,score_reason,awarded_points,editable_until,rubric_version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,1,?,?,NULL,NULL,0,NULL,NULL,?,?)",
        memberId,
        directionId,
        type,
        type.equals("DAILY") ? submittedAt : null,
        type.equals("WEEKLY") ? submittedAt : null,
        type.equals("WEEKLY") ? submittedAt : null,
        status,
        status.equals("SUBMITTED") ? submittedAt : null,
        scoreStatus,
        submittedAt,
        submittedAt);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private void versionAndTask(long reportId, String status) {
    jdbc.update(
        "INSERT INTO report_version(report_id,version,content_json,content_hash,edited_by,created_at,is_current)"
            + " VALUES(?,1,'{}',UNHEX(?),30,UTC_TIMESTAMP(6),true)",
        reportId,
        "00".repeat(32));
    long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO report_scoring_task(report_id,report_version_id,status,attempt,score,reason,safe_error_code,created_at,updated_at)"
            + " VALUES(?,?,?,0,NULL,NULL,'PROVIDER_ERROR',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        reportId,
        versionId,
        status);
  }

  private void points(int memberId, int delta, Timestamp at) {
    jdbc.update(
        "INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key)"
            + " VALUES(?,NULL,NULL,?,'SUBMISSION_BASE',?,?, 'leader-dashboard-v1',?)",
        memberId,
        delta,
        at,
        at,
        "leader-" + memberId + "-" + delta + "-" + at.getTime());
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

  private Timestamp businessNow() {
    return Timestamp.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).atTime(0, 30)
        .toInstant(ZoneOffset.ofHours(8)));
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
