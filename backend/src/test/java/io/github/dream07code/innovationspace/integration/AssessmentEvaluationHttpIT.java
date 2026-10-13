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
    properties = {
      "app.auth.initial-password=synthetic-initial",
      "app.ai.enabled=true",
      "app.ai.provider=mock",
      "app.ai.api-key=synthetic-not-a-real-key",
      "app.ai.model=mock-assessment-model",
      "app.ai.worker-scheduling-enabled=false"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AssessmentEvaluationHttpIT {
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
  String submissionId;

  @BeforeEach
  void fixture() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_judge_result","assessment_submission_code","assessment_submission","assessment_problem_link","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
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
        {"title":"AI评分题","summary":"评估代码质量","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """, UUID.randomUUID().toString());
    assertEquals(201, problem.statusCode());
    problemId = HttpTestSupport.json(problem).at("/data/id").asText();
    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"AI评分考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"problemIds":["%s"]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemId), UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).at("/data/id").asText();
    assertEquals(200, send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", UUID.randomUUID().toString()).statusCode());
    var submission = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main { /* private ai code marker */ }"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(201, submission.statusCode());
    submissionId = HttpTestSupport.json(submission).at("/data/id").asText();
    awaitJudged();
  }

  @Test
  void aiEvaluationUsesSafeMetadataAndFeedsIndependentResult() throws Exception {
    var evaluation = awaitEvaluation(studentToken);
    ContractAssertions.response("GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", evaluation);
    var data = HttpTestSupport.json(evaluation).at("/data");
    assertEquals("COMPLETE", data.at("/status").asText());
    assertEquals(85, data.at("/score").asInt());
    assertEquals("mock-assessment-model", data.at("/model").asText());
    assertFalse(evaluation.body().contains("private ai code marker"));
    assertFalse(evaluation.body().contains("codeText"));
    assertFalse(evaluation.body().contains("rawProviderResponse"));

    var results = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, results.statusCode());
    assertEquals(85, HttpTestSupport.json(results).at("/data/items/0/aiScore").asInt());
    assertEquals(85, HttpTestSupport.json(results).at("/data/items/0/finalScore").asInt());
    assertEquals("AI", HttpTestSupport.json(results).at("/data/items/0/scoreSource").asText());
    assertEquals("COMPLETE", HttpTestSupport.json(results).at("/data/items/0/resultStatus").asText());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
  }

  @Test
  void failedAiIsUnavailableAndLateAiCannotReplaceHumanScore() throws Exception {
    jdbc.update("UPDATE ai_evaluation SET status='FAILED',score=NULL,reason='synthetic provider unavailable',model='mock-assessment-model' WHERE submission_id=? ORDER BY version DESC LIMIT 1", submissionId);
    var failed = send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null);
    assertEquals(200, failed.statusCode());
    assertEquals("FAILED", HttpTestSupport.json(failed).at("/data/status").asText());
    assertTrue(HttpTestSupport.json(failed).at("/data/score").isNull());

    var failedResults = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals("UNAVAILABLE", HttpTestSupport.json(failedResults).at("/data/items/0/scoreSource").asText());
    assertEquals("NEEDS_REVIEW", HttpTestSupport.json(failedResults).at("/data/items/0/resultStatus").asText());
    assertTrue(HttpTestSupport.json(failedResults).at("/data/items/0/finalScore").isNull());

    long submissionVersion = HttpTestSupport.json(awaitSubmission()).at("/data/version").asLong();
    var review = send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", """
        {"submissionVersion":%d,"reviewVersion":1,"score":92,"comment":"人工批阅意见"}
        """.formatted(submissionVersion), UUID.randomUUID().toString());
    assertEquals(201, review.statusCode());
    jdbc.update("INSERT INTO ai_evaluation(submission_id,version,status,score,reason,model,rubric_version,created_at) VALUES(?,3,'COMPLETE',20,'late synthetic score','mock-assessment-model','assessment-rubric-v1',UTC_TIMESTAMP(6))", submissionId);

    var results = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(92, HttpTestSupport.json(results).at("/data/items/0/humanScore").asInt());
    assertEquals(20, HttpTestSupport.json(results).at("/data/items/0/aiScore").asInt());
    assertEquals(92, HttpTestSupport.json(results).at("/data/items/0/finalScore").asInt());
    assertEquals("HUMAN", HttpTestSupport.json(results).at("/data/items/0/scoreSource").asText());
  }

  @Test
  void evaluationVisibilityFollowsSubmissionOwnership() throws Exception {
    assertEquals(404, send(otherStudentToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null).statusCode());
    assertEquals(200, send(leaderToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null).statusCode());
    assertEquals(200, send(adminToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null).statusCode());
  }

  private HttpResponse<String> awaitEvaluation(String token) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var response = send(token, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null);
      if (response.statusCode() == 200 && !"PENDING".equals(HttpTestSupport.json(response).at("/data/status").asText())) return response;
      Thread.sleep(25);
    }
    return send(token, "GET", "/api/v1/assessment-submissions/" + submissionId + "/ai-evaluation", null, null);
  }

  private HttpResponse<String> awaitSubmission() throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var response = send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId, null, null);
      if (response.statusCode() == 200 && !"RECEIVED".equals(HttpTestSupport.json(response).at("/data/status").asText())) return response;
      Thread.sleep(25);
    }
    return send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId, null, null);
  }

  private void awaitJudged() throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var response = send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId, null, null);
      String status = HttpTestSupport.json(response).at("/data/status").asText();
      if ("JUDGED".equals(status) || "FAILED".equals(status)) return;
      Thread.sleep(25);
    }
    fail("Timed out waiting for OJ judgment");
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
