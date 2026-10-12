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
class AdminDashboardHttpIT {
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
    seedMember(11, "STUDENT", 1);
    seedMember(12, "STUDENT", 2);
    jdbc.update("UPDATE member SET membership_status='REMOVED',removed_at=UTC_TIMESTAMP(6) WHERE id=12");
    seedMember(13, "STUDENT", 2);
    seedMember(20, "DIRECTION_LEADER", 1);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(30,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(20,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(30).accessToken();
    leaderToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(10).accessToken();
  }

  @Test
  void adminDashboardReturnsRoleScopedAggregatesAndTrends() throws Exception {
    Timestamp businessNow = businessNow();
    long daily10 = report(10, 1, "DAILY", businessNow, "SUBMITTED", businessNow, "SCORED");
    long weekly10 = report(10, 1, "WEEKLY", businessNow, "SUBMITTED", businessNow, "SCORED");
    long daily11 = report(11, 1, "DAILY", businessNow, "SUBMITTED", businessNow, "REVIEW_REQUIRED");
    long daily12 = report(12, 2, "DAILY", businessNow, "SUBMITTED", businessNow, "QUEUED");
    versionAndTask(daily10, "SCORED");
    versionAndTask(daily11, "FAILED");
    review(daily11, "PENDING");
    points(10, 2, businessNow);
    points(10, 1, businessNow);
    assessmentSubmission(10);

    var response = send(adminToken, "GET", "/api/v1/dashboard/admin", null);
    assertEquals(200, response.statusCode());
    ContractAssertions.response("GET", "/api/v1/dashboard/admin", response);
    var data = HttpTestSupport.json(response).at("/data");
    assertEquals("30d", data.at("/range").asText());
    assertEquals(3, data.at("/overview/activeStudents").asInt());
    assertEquals(4, data.at("/overview/totalStudents").asInt());
    assertEquals(1, data.at("/overview/directionLeaders").asInt());
    assertEquals(1, data.at("/overview/removedStudents").asInt());
    assertEquals(2, data.at("/overview/todayDailySubmitted").asInt());
    assertEquals(1, data.at("/overview/failedAi").asInt());
    assertEquals(1, data.at("/overview/pendingReview").asInt());
    assertEquals(1, data.at("/overview/pendingOj").asInt());
    assertEquals(30, data.at("/trends").size());
    assertEquals(2, data.at("/trends/29/dailySubmitted").asInt());
    assertEquals(3, data.at("/trends/29/pointsAdded").asInt());
    assertEquals(7, data.at("/directions").size());
    assertTrue(data.at("/attention").size() >= 3);
    assertFalse(response.body().contains("MOCK10"));
    assertFalse(response.body().contains("contact"));
    assertFalse(response.body().contains("content"));
    assertFalse(response.body().contains("codeText"));
  }

  @Test
  void adminDashboardRejectsInvalidRangeAndOtherRoles() throws Exception {
    assertEquals(400, send(adminToken, "GET", "/api/v1/dashboard/admin?range=99d", null).statusCode());
    assertEquals(200, send(adminToken, "GET", "/api/v1/dashboard/admin?range=7d", null).statusCode());
    assertEquals(403, send(leaderToken, "GET", "/api/v1/dashboard/admin", null).statusCode());
    assertEquals(403, send(studentToken, "GET", "/api/v1/dashboard/admin", null).statusCode());
  }

  @Test
  void adminDashboardDoesNotMutateBusinessData() throws Exception {
    report(10, 1, "DAILY", businessNow(), "SUBMITTED", businessNow(), "QUEUED");
    points(10, 2, businessNow());
    Map<String, Object> before = counts();
    assertEquals(200, send(adminToken, "GET", "/api/v1/dashboard/admin?range=7d", null).statusCode());
    assertEquals(before, counts());
  }

  @Test
  void adminDashboardDateBucketUsesBusinessCalendarAndBoundsAttention() throws Exception {
    Timestamp businessToday = businessNow();
    for (int id = 100; id < 112; id++) {
      seedMember(id, "STUDENT", 1);
      long reportId = report(id, 1, "DAILY", businessToday, "SUBMITTED", businessToday, "REVIEW_REQUIRED");
      versionAndTask(reportId, "FAILED");
      review(reportId, "PENDING");
    }

    var response = send(adminToken, "GET", "/api/v1/dashboard/admin?range=7d", null);
    assertEquals(200, response.statusCode());
    var data = HttpTestSupport.json(response).at("/data");
    assertEquals(7, data.at("/trends").size());
    assertEquals(12, data.at("/trends/6/dailySubmitted").asInt());
    assertEquals(10, data.at("/attention").size());
    var body = response.body();
    for (String forbidden : new String[] {"studentNo", "contact", "content", "codeText", "codeLocator"}) {
      assertFalse(body.contains(forbidden), forbidden);
    }
  }

  private Map<String, Object> counts() {
    return jdbc.queryForMap(
        "SELECT (SELECT COUNT(*) FROM point_ledger) points,(SELECT COUNT(*) FROM report) reports,"
            + "(SELECT COUNT(*) FROM member) members,(SELECT COUNT(*) FROM assessment_submission) submissions");
  }

  private long report(
      int memberId,
      int directionId,
      String type,
      Timestamp submittedAt,
      String status,
      Timestamp businessAt,
      String scoreStatus) {
    jdbc.update(
        "INSERT INTO report(member_id,direction_id,report_type,report_date,week_start,week_end,status,current_version,first_submitted_at,score_status,final_score,score_reason,awarded_points,editable_until,rubric_version,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,1,?,?,NULL,NULL,0,NULL,NULL,?,?)",
        memberId,
        directionId,
        type,
        type.equals("DAILY") ? businessAt : null,
        type.equals("WEEKLY") ? businessAt : null,
        type.equals("WEEKLY") ? businessAt : null,
        status,
        status.equals("SUBMITTED") ? submittedAt : null,
        scoreStatus,
        businessAt,
        businessAt);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private long versionAndTask(long reportId, String taskStatus) {
    jdbc.update(
        "INSERT INTO report_version(report_id,version,content_json,content_hash,edited_by,created_at,is_current)"
            + " VALUES(?,1,'{}',UNHEX(?),30,UTC_TIMESTAMP(6),true)",
        reportId,
        "00".repeat(32));
    long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO report_scoring_task(report_id,report_version_id,status,attempt,score,reason,safe_error_code,created_at,updated_at)"
            + " VALUES(?,?,?,0,NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        reportId,
        versionId,
        taskStatus,
        taskStatus.equals("FAILED") ? "PROVIDER_ERROR" : null);
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private void review(long reportId, String status) {
    long versionId = jdbc.queryForObject(
        "SELECT id FROM report_version WHERE report_id=? ORDER BY version DESC LIMIT 1", Long.class, reportId);
    jdbc.update(
        "INSERT INTO report_review(report_id,report_version_id,status,original_score,resolved_score,reason,resolved_by,version,created_at,resolved_at)"
            + " VALUES(?,?,?,NULL,NULL,'synthetic review',NULL,1,UTC_TIMESTAMP(6),NULL)",
        reportId,
        versionId,
        status);
  }

  private void points(int memberId, int delta, Timestamp at) {
    jdbc.update(
        "INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key)"
            + " VALUES(?,NULL,NULL,?,'SUBMISSION_BASE',?,?, 'dashboard-v1',?)",
        memberId,
        delta,
        at,
        at,
        "dashboard-" + memberId + "-" + delta + "-" + at.getTime());
  }

  private void assessmentSubmission(int memberId) {
    jdbc.update(
        "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,status,version,created_by,created_at,updated_at)"
            + " VALUES('题','x','x','x','x','[]','EASY','[]',1000,65536,'[\"java\"]','PUBLISHED',1,30,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    long problemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO assessment(title,description,direction_id,status,start_at,end_at,allowed_languages,version,created_by,created_at,updated_at)"
            + " VALUES('考核','x',1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),'[\"java\"]',1,30,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    long assessmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    jdbc.update(
        "INSERT INTO assessment_problem_link(assessment_id,problem_id,position) VALUES(?,?,1)",
        assessmentId,
        problemId);
    jdbc.update(
        "INSERT INTO assessment_submission(assessment_id,problem_id,member_id,language,code_locator,code_hash,status,version,submitted_at,updated_at)"
            + " VALUES(?,?,?,'java',NULL,NULL,'JUDGING',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        assessmentId,
        problemId,
        memberId);
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,contact,membership_status,joined_at,membership_started_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,'synthetic-contact','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id,
        "MOCK" + id,
        directionId,
        "成员" + id);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id,
        id,
        "MOCK" + id,
        role,
        "成员" + id);
  }

  private Timestamp businessNow() {
    return Timestamp.from(
        LocalDate.now(ZoneId.of("Asia/Shanghai")).atTime(0, 30).toInstant(ZoneOffset.ofHours(8)));
  }

  private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      return client.send(
          request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
