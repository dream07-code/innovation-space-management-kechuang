package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.ContractAssertions;
import io.github.dream07code.testsupport.HttpTestSupport;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OjFixtureSupport;
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

/** 考核计分：自动分按通过的测试点计算，人工评分覆盖自动分；不再有 AI 评分环节。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.auth.initial-password=synthetic-initial"})
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AssessmentScoringHttpIT {
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
  String problemId;
  String assessmentId;
  String submissionId;

  @BeforeEach
  void fixture() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result","assessment_submission_code","assessment_submission","assessment_answer_sheet_item","assessment_answer_sheet","assessment_item","quiz_question","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
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

    problemId =
        OjFixtureSupport.seedStdioProblem(
            jdbc,
            "按测试点计分题",
            "[\"java\"]",
            java.util.List.of(new String[] {"1", "1"}, new String[] {"2", "2"}),
            java.util.List.of());
    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"计分考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":"%s","score":100}]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemId), UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).at("/data/id").asText();
    assertEquals(200, send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", UUID.randomUUID().toString()).statusCode());
    var submission = send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/submissions", """
        {"problemId":"%s","language":"java","code":"class Main { /* private solution marker */ }"}
        """.formatted(problemId), UUID.randomUUID().toString());
    assertEquals(201, submission.statusCode());
    submissionId = HttpTestSupport.json(submission).at("/data/id").asText();
    awaitJudged();
  }

  @Test
  void automaticScoreComesFromPassedTestCasesAndNeedsNoAi() throws Exception {
    var results =
        send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, results.statusCode());
    ContractAssertions.response("GET", "/api/v1/assessments/" + assessmentId + "/results", results);

    var row = HttpTestSupport.json(results).at("/data/items/0");
    assertEquals("ACCEPTED", row.at("/technicalVerdict").asText());
    assertEquals(2, row.at("/passedTestCases").asInt());
    assertEquals(2, row.at("/totalTestCases").asInt());
    // 2/2 通过 → 100 分，来源为按测试点自动计分
    assertEquals(100, row.at("/autoScore").asInt());
    assertEquals(100, row.at("/finalScore").asInt());
    assertEquals("AUTO", row.at("/scoreSource").asText());
    assertEquals("COMPLETE", row.at("/resultStatus").asText());
    assertTrue(row.at("/humanScore").isNull());
    // 不再返回任何 AI 评分字段
    assertFalse(row.has("aiScore"));
    assertFalse(row.has("aiStatus"));
    assertFalse(row.has("aiReason"));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger", Integer.class));
  }

  @Test
  void partialPassesScoreProportionally() throws Exception {
    jdbc.update(
        "UPDATE oj_judge_result SET verdict='WRONG_ANSWER',passed_test_cases=1,total_test_cases=2 WHERE submission_id=?",
        submissionId);

    var results =
        send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    var row = HttpTestSupport.json(results).at("/data/items/0");
    assertEquals(50, row.at("/autoScore").asInt());
    assertEquals(50, row.at("/finalScore").asInt());
    assertEquals("AUTO", row.at("/scoreSource").asText());
  }

  @Test
  void humanReviewOverridesAutomaticScore() throws Exception {
    long submissionVersion = HttpTestSupport.json(awaitJudgedSubmission()).at("/data/version").asLong();
    var review =
        send(
            leaderToken,
            "POST",
            "/api/v1/assessment-submissions/" + submissionId + "/reviews",
            """
            {"submissionVersion":%d,"reviewVersion":1,"score":92,"comment":"人工复核调整"}
            """
                .formatted(submissionVersion),
            UUID.randomUUID().toString());
    assertEquals(201, review.statusCode());

    var results =
        send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    var row = HttpTestSupport.json(results).at("/data/items/0");
    assertEquals(100, row.at("/autoScore").asInt());
    assertEquals(92, row.at("/humanScore").asInt());
    assertEquals(92, row.at("/finalScore").asInt());
    assertEquals("HUMAN", row.at("/scoreSource").asText());
    assertEquals("COMPLETE", row.at("/resultStatus").asText());
  }

  @Test
  void internalJudgeErrorLeavesScoreForManualReview() throws Exception {
    jdbc.update(
        "UPDATE oj_judge_result SET verdict='INTERNAL_ERROR',passed_test_cases=0 WHERE submission_id=?",
        submissionId);

    var results =
        send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    var row = HttpTestSupport.json(results).at("/data/items/0");
    // 判题环境问题不自动给 0 分，交给人工
    assertTrue(row.at("/autoScore").isNull());
    assertTrue(row.at("/finalScore").isNull());
    assertEquals("PENDING", row.at("/scoreSource").asText());
    assertEquals("NEEDS_REVIEW", row.at("/resultStatus").asText());
  }

  @Test
  void studentsOnlySeeTheirOwnResults() throws Exception {
    var otherStudent =
        send(otherStudentToken, "GET", "/api/v1/assessments/" + assessmentId + "/results?page=1&size=20", null, null);
    assertEquals(200, otherStudent.statusCode());
    assertEquals(0, HttpTestSupport.json(otherStudent).at("/data/total").asInt());
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, "MOCK" + id, directionId, "成员" + id);
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, id, "MOCK" + id, role, "成员" + id);
  }

  private HttpResponse<String> awaitJudgedSubmission() throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var response = send(studentToken, "GET", "/api/v1/assessment-submissions/" + submissionId, null, null);
      String status = HttpTestSupport.json(response).at("/data/status").asText();
      if ("JUDGED".equals(status) || "FAILED".equals(status)) return response;
      Thread.sleep(25);
    }
    fail("Timed out waiting for OJ judgment");
    return null;
  }

  private void awaitJudged() throws Exception {
    awaitJudgedSubmission();
  }

  private HttpResponse<String> send(String token, String method, String path, String body, String key)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json");
      if (key != null) request.header("Idempotency-Key", key);
      return client.send(
          request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
