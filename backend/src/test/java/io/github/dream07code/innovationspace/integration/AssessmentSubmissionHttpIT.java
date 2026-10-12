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
class AssessmentSubmissionHttpIT {
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
    seedMember(12, "STUDENT", 2);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    studentToken = sessions.issue(10).accessToken();
    otherStudentToken = sessions.issue(12).accessToken();

    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", """
        {"title":"提交题","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java","python"]}
        """, UUID.randomUUID().toString());
    assertEquals(201, problem.statusCode());
    problemId = HttpTestSupport.json(problem).at("/data/id").asText();

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"提交考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java","python"],"problemIds":["%s"]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemId), UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).at("/data/id").asText();
    var publish = send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", UUID.randomUUID().toString());
    assertEquals(200, publish.statusCode());
  }

  @Test
  void studentSubmitsCodeAndIdempotentReplayDoesNotExposeCode() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = """
        {"problemId":"%s","language":"java","code":"class Main { /* private solution marker */ }"}
        """.formatted(problemId);
    var created = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", body, key);
    assertEquals(201, created.statusCode());
    ContractAssertions.response("POST", "/api/v1/assessments/" + assessmentId + "/submissions", created);
    String id = HttpTestSupport.json(created).at("/data/id").asText();
    assertEquals("10", HttpTestSupport.json(created).at("/data/memberId").asText());
    assertFalse(created.body().contains("private solution marker"));
    assertFalse(created.body().contains("codeLocator"));
    assertFalse(created.body().contains("codeText"));

    var replay = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", body, key);
    assertEquals(201, replay.statusCode());
    assertEquals(id, HttpTestSupport.json(replay).at("/data/id").asText());
    assertFalse(replay.body().contains("private solution marker"));

    var detail = awaitJudged(studentToken, id);
    ContractAssertions.response("GET", "/api/v1/assessment-submissions/" + id, detail);
    assertEquals("JUDGED", HttpTestSupport.json(detail).at("/data/status").asText());
    assertEquals("ACCEPTED", HttpTestSupport.json(detail).at("/data/judgeResult/verdict").asText());
    assertFalse(detail.body().contains("private solution marker"));
    assertFalse(detail.body().contains("codeLocator"));

    var auditRows =
        jdbc.queryForList(
            "SELECT action,summary,detail FROM assessment_audit WHERE resource_type='SUBMISSION' AND resource_id=?",
            id);
    assertTrue(auditRows.stream().anyMatch(row -> "SUBMISSION_CREATED".equals(row.get("action"))));
    assertTrue(auditRows.stream().anyMatch(row -> "OJ_JUDGED".equals(row.get("action"))));
    assertFalse(auditRows.toString().contains("private solution marker"));
    assertFalse(auditRows.toString().contains("class Main"));
  }

  @Test
  void languageOwnershipAndRoleRulesAreEnforced() throws Exception {
    var invalidLanguage = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"cpp","code":"int main(){}"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(400, invalidLanguage.statusCode());

    var leaderSubmit = send(leaderToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main{}"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(403, leaderSubmit.statusCode());

    var crossDirection = send(otherStudentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main{}"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(404, crossDirection.statusCode());

    var created = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main{}"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(201, created.statusCode());
    String id = HttpTestSupport.json(created).at("/data/id").asText();
    assertEquals(404, send(otherStudentToken, "GET", "/api/v1/assessment-submissions/" + id, null, null).statusCode());
  }

  private HttpResponse<String> awaitJudged(String token, String id) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    HttpResponse<String> response = send(token, "GET", "/api/v1/assessment-submissions/" + id, null, null);
    while (System.nanoTime() < deadline && !"JUDGED".equals(HttpTestSupport.json(response).at("/data/status").asText()) && !"FAILED".equals(HttpTestSupport.json(response).at("/data/status").asText())) {
      Thread.sleep(25);
      response = send(token, "GET", "/api/v1/assessment-submissions/" + id, null, null);
    }
    return response;
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
