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
      "app.ai.enabled=false",
      "app.ai.worker-scheduling-enabled=false"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AssessmentReviewHttpIT {
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
  String otherLeaderToken;
  String problemId;
  String assessmentId;
  String submissionId;
  long submissionVersion;

  @BeforeEach
  void fixture() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result","assessment_submission_code","assessment_submission","assessment_answer_sheet_item","assessment_answer_sheet","assessment_item","quiz_question","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(11, "DIRECTION_LEADER", 1);
    seedMember(12, "DIRECTION_LEADER", 2);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(12,2,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    studentToken = sessions.issue(10).accessToken();
    otherLeaderToken = sessions.issue(12).accessToken();

    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", """
        {"title":"人工批阅题","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """, UUID.randomUUID().toString());
    assertEquals(201, problem.statusCode());
    problemId = HttpTestSupport.json(problem).at("/data/id").asText();
    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"人工批阅考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":"%s","score":100}]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemId), UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).at("/data/id").asText();
    assertEquals(200, send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", UUID.randomUUID().toString()).statusCode());
    var submission = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main { /* private review code marker */ }"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(201, submission.statusCode());
    submissionId = HttpTestSupport.json(submission).at("/data/id").asText();
    awaitJudged();
    submissionVersion = HttpTestSupport.json(send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId, null, null)).at("/data/version").asLong();
  }

  @Test
  void humanReviewIsIdempotentVersionedAndOverridesAi() throws Exception {
    int pointsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class);
    String key = UUID.randomUUID().toString();
    String body = """
        {"submissionVersion":%d,"reviewVersion":1,"score":92,"comment":"结构清晰，边界处理完整"}
        """.formatted(submissionVersion);
    var created = send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", body, key);
    assertEquals(201, created.statusCode());
    ContractAssertions.response("POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", created);
    String reviewId = HttpTestSupport.json(created).at("/data/id").asText();
    assertEquals("ACTIVE", HttpTestSupport.json(created).at("/data/status").asText());
    assertEquals("成员10", HttpTestSupport.json(created).at("/data/memberName").asText());
    assertEquals("MOCK10", HttpTestSupport.json(created).at("/data/memberStudentNo").asText());
    assertEquals("成员11", HttpTestSupport.json(created).at("/data/reviewerName").asText());
    assertFalse(created.body().contains("private review code marker"));

    var replay = send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", body, key);
    assertEquals(201, replay.statusCode());
    assertEquals(reviewId, HttpTestSupport.json(replay).at("/data/id").asText());

    var reviews = send(leaderToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/reviews?page=1&size=20", null, null);
    assertEquals(200, reviews.statusCode());
    ContractAssertions.response("GET", "/api/v1/assessment-submissions/" + submissionId + "/reviews", reviews);
    assertEquals(1, HttpTestSupport.json(reviews).at("/data/total").asInt());

    // 人工评分覆盖自动分：自动分仍按测试点计算，但最终分取人工分
    var results = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(92, HttpTestSupport.json(results).at("/data/items/0/humanScore").asInt());
    assertEquals(92, HttpTestSupport.json(results).at("/data/items/0/finalScore").asInt());
    assertEquals("HUMAN", HttpTestSupport.json(results).at("/data/items/0/scoreSource").asText());
    assertEquals(pointsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
  }

  @Test
  void staleVersionsAndUnauthorizedReviewersAreRejected() throws Exception {
    String first = """
        {"submissionVersion":%d,"reviewVersion":1,"score":88,"comment":"第一次批阅"}
        """.formatted(submissionVersion);
    assertEquals(201, send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", first, UUID.randomUUID().toString()).statusCode());

    assertEquals(409, send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", """
        {"submissionVersion":%d,"reviewVersion":1,"score":77,"comment":"重复版本"}
        """.formatted(submissionVersion), UUID.randomUUID().toString()).statusCode());
    assertEquals(409, send(leaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", """
        {"submissionVersion":99,"reviewVersion":2,"score":77,"comment":"旧提交版本"}
        """.formatted(), UUID.randomUUID().toString()).statusCode());

    String second = """
        {"submissionVersion":%d,"reviewVersion":2,"score":81,"comment":"复核后调整"}
        """.formatted(submissionVersion);
    var updated = send(adminToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", second, UUID.randomUUID().toString());
    assertEquals(201, updated.statusCode());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assessment_review WHERE submission_id=? AND status='SUPERSEDED'", Integer.class, submissionId));
    assertEquals(2, HttpTestSupport.json(send(adminToken, "GET", "/api/v1/assessment-submissions/" + submissionId + "/reviews?page=1&size=20", null, null)).at("/data/total").asInt());
    assertEquals(81, HttpTestSupport.json(send(adminToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null)).at("/data/items/0/finalScore").asInt());

    assertEquals(403, send(studentToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", second, UUID.randomUUID().toString()).statusCode());
    assertEquals(404, send(otherLeaderToken, "POST", "/api/v1/assessment-submissions/" + submissionId + "/reviews", second, UUID.randomUUID().toString()).statusCode());
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
