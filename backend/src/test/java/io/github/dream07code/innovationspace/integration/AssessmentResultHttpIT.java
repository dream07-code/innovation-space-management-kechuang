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
import java.time.OffsetDateTime;
import java.util.UUID;
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
class AssessmentResultHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordService passwords;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String leaderToken;
  String studentToken;
  String otherStudentToken;
  String problemId;
  String assessmentId;

  @BeforeEach
  void fixture() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_judge_result","assessment_submission","assessment_problem_link","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(11, "DIRECTION_LEADER", 1);
    seedMember(12, "STUDENT", 1);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    studentToken = sessions.issue(10).accessToken();
    otherStudentToken = sessions.issue(12).accessToken();

    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", """
        {"title":"结果题","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"]}
        """, UUID.randomUUID().toString());
    assertEquals(201, problem.statusCode());
    problemId = HttpTestSupport.json(problem).at("/data/id").asText();

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"结果考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"problemIds":["%s"]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemId), UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).at("/data/id").asText();
    var publish = send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", UUID.randomUUID().toString());
    assertEquals(200, publish.statusCode());
  }

  @Test
  void resultsAggregateSafeTechnicalSummariesAndDoNotWritePoints() throws Exception {
    int pointsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class);
    submit(studentToken, "class Main { accepted marker }");
    submit(otherStudentToken, "class Main { wrong-answer marker }");
    awaitResults(2);

    var studentResults = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, studentResults.statusCode());
    ContractAssertions.response("GET", "/api/v1/assessments/" + assessmentId + "/results", studentResults);
    var studentData = HttpTestSupport.json(studentResults).at("/data");
    assertEquals(1, studentData.at("/total").asInt());
    assertEquals("ACCEPTED", studentData.at("/items/0/technicalVerdict").asText());
    assertEquals("PENDING", studentData.at("/items/0/scoreSource").asText());
    assertEquals("PENDING", studentData.at("/items/0/resultStatus").asText());
    assertTrue(studentData.at("/items/0/finalScore").isNull());
    assertFalse(studentResults.body().contains("accepted marker"));
    assertFalse(studentResults.body().contains("codeText"));
    assertFalse(studentResults.body().contains("codeLocator"));

    var leaderResults = send(leaderToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, leaderResults.statusCode());
    assertEquals(2, HttpTestSupport.json(leaderResults).at("/data/total").asInt());
    var adminResults = send(adminToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, adminResults.statusCode());
    assertEquals(2, HttpTestSupport.json(adminResults).at("/data/total").asInt());
    assertEquals(pointsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
  }

  @Test
  void pendingSubmissionsAreNotReturnedAsCompletedResults() throws Exception {
    var response = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, response.statusCode());
    assertEquals(0, HttpTestSupport.json(response).at("/data/total").asInt());
    assertEquals(404, send(otherStudentToken, "GET", "/api/v1/assessments/999999/results?page=1&size=20", null, null).statusCode());
  }

  private void submit(String token, String code) throws Exception {
    var response = send(token, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"%s"}
        """.formatted(problemId, code.replace("\"", "\\\"")), UUID.randomUUID().toString());
    assertEquals(201, response.statusCode());
  }

  private void awaitResults(int expected) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var response = send(adminToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
      if (response.statusCode() == 200 && HttpTestSupport.json(response).at("/data/total").asInt() >= expected) return;
      Thread.sleep(25);
    }
    fail("Timed out waiting for assessment results");
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, "MOCK" + id, directionId, "成员" + id);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> send(String token, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .header("Authorization", "Bearer " + token)
          .header("Content-Type", "application/json");
      if (key != null) request.header("Idempotency-Key", key);
      return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
