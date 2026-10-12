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
class StudentDashboardHttpIT {
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
  String otherStudentToken;

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
    seedMember(11, "STUDENT", 1);
    seedMember(20, "DIRECTION_LEADER", 1);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(30,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(20,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(30).accessToken();
    leaderToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(10).accessToken();
    otherStudentToken = sessions.issue(11).accessToken();
  }

  @Test
  void studentDashboardReturnsOnlyOwnSafeSummaries() throws Exception {
    Timestamp now = businessNow();
    long ownDaily = report(10, 1, "DAILY", now, "SUBMITTED", "SCORED");
    report(10, 1, "WEEKLY", now, "SUBMITTED", "SCORED");
    report(11, 1, "DAILY", now, "SUBMITTED", "SCORED");
    points(10, 3, now);
    points(10, 2, now);
    points(11, 9, now);

    var response = send(studentToken, "GET", "/api/v1/dashboard/student", null);
    assertEquals(200, response.statusCode());
    ContractAssertions.response("GET", "/api/v1/dashboard/student", response);
    var data = HttpTestSupport.json(response).at("/data");
    assertEquals("7d", data.at("/range").asText());
    assertEquals(5, data.at("/overview/points").asInt());
    assertEquals(2, data.at("/overview/overallRank").asInt());
    assertEquals(1, data.at("/progress/dailySubmitted").asInt());
    assertEquals(1, data.at("/progress/weeklySubmitted").asInt());
    assertEquals(2, data.at("/reports").size());
    assertTrue(data.at("/assessments").isArray());
    assertTrue(data.at("/points").size() >= 2);
    assertEquals(2, data.at("/rankingPreview/overallCount").asInt());
    assertFalse(response.body().contains("MOCK11"));
    assertFalse(response.body().contains("studentNo"));
    assertFalse(response.body().contains("contact"));
    assertFalse(response.body().contains("codeText"));
  }

  @Test
  void studentRoleMatrixRangeAndMutationRulesAreEnforced() throws Exception {
    report(10, 1, "DAILY", businessNow(), "SUBMITTED", "QUEUED");
    Map<String, Object> before = counts();
    assertEquals(403, send(adminToken, "GET", "/api/v1/dashboard/student", null).statusCode());
    assertEquals(403, send(leaderToken, "GET", "/api/v1/dashboard/student", null).statusCode());
    assertEquals(400, send(studentToken, "GET", "/api/v1/dashboard/student?range=99d", null).statusCode());
    assertEquals(200, send(studentToken, "GET", "/api/v1/dashboard/student?range=30d", null).statusCode());
    assertEquals(before, counts());
  }

  private Map<String, Object> counts() {
    return jdbc.queryForMap(
        "SELECT (SELECT COUNT(*) FROM point_ledger) points,(SELECT COUNT(*) FROM report) reports,"
            + "(SELECT COUNT(*) FROM member) members,(SELECT COUNT(*) FROM assessment_submission) submissions");
  }

  private long report(
      int memberId, int directionId, String type, Timestamp submittedAt, String status, String scoreStatus) {
    jdbc.update(
        "INSERT INTO report(member_id,direction_id,report_type,report_date,week_start,week_end,status,current_version,first_submitted_at,score_status,final_score,score_reason,awarded_points,editable_until,rubric_version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,1,?,?,NULL,NULL,0,NULL,NULL,?,?)",
        memberId, directionId, type, type.equals("DAILY") ? submittedAt : null,
        type.equals("WEEKLY") ? submittedAt : null, type.equals("WEEKLY") ? submittedAt : null,
        status, status.equals("SUBMITTED") ? submittedAt : null, scoreStatus, submittedAt, submittedAt);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private void points(int memberId, int delta, Timestamp at) {
    jdbc.update(
        "INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key)"
            + " VALUES(?,NULL,NULL,?,'SUBMISSION_BASE',?,?, 'student-dashboard-v1',?)",
        memberId, delta, at, at, "student-" + memberId + "-" + delta + "-" + at.getTime());
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
