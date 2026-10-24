package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.PasswordService;
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
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result","assessment_submission","assessment_answer_sheet_item","assessment_answer_sheet","assessment_item","quiz_question","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
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

    problemId =
        OjFixtureSupport.seedStdioProblem(
            jdbc, "结果题", "[\"java\"]",
            java.util.List.<String[]>of(new String[] {"1", "1"}), java.util.List.of());

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"结果考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":"%s","score":100}]}
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
    assertEquals("10", studentData.at("/items/0/memberId").asText());
    assertEquals("成员10", studentData.at("/items/0/memberName").asText());
    assertEquals("MOCK10", studentData.at("/items/0/memberStudentNo").asText());
    assertEquals("ACCEPTED", studentData.at("/items/0/technicalVerdict").asText());
    // V11 起按测试点自动计分：mock 判 ACCEPTED（全过）→ AUTO/COMPLETE，
    // 挂题分值 100（V12 前旧口径），finalScore 即 100。
    assertEquals("AUTO", studentData.at("/items/0/scoreSource").asText());
    assertEquals("COMPLETE", studentData.at("/items/0/resultStatus").asText());
    assertEquals(100, studentData.at("/items/0/finalScore").asInt());
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


  @Test
  void memberScoresAggregateMixedPaperByItemScoreScale() throws Exception {
    // 独立小场景：2 程序题(20+30) + 2 客观题(5+5)。
    String problemAId =
        OjFixtureSupport.seedStdioProblem(
            jdbc, "混合甲", "[\"java\"]",
            java.util.List.<String[]>of(new String[] {"1", "1"}), java.util.List.of());
    String problemBId =
        OjFixtureSupport.seedStdioProblem(
            jdbc, "混合乙", "[\"java\"]",
            java.util.List.<String[]>of(new String[] {"1", "1"}), java.util.List.of());
    var quizRight = send(adminToken, "POST", "/api/v1/quiz-questions",
        "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"答对\",\"options\":[\"甲\",\"乙\"],\"answer\":\"A\"}",
        java.util.UUID.randomUUID().toString());
    var quizWrong = send(adminToken, "POST", "/api/v1/quiz-questions",
        "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"答错\",\"options\":[\"甲\",\"乙\"],\"answer\":\"A\"}",
        java.util.UUID.randomUUID().toString());
    String rightId = HttpTestSupport.json(quizRight).at("/data/id").asText();
    String wrongId = HttpTestSupport.json(quizWrong).at("/data/id").asText();
    var mixed = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"混合汇总","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":"%s","score":20},{"type":"PROGRAM","refId":"%s","score":30},{"type":"QUIZ","refId":"%s","score":5},{"type":"QUIZ","refId":"%s","score":5}]}
        """.formatted(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), problemAId, problemBId, rightId, wrongId),
        java.util.UUID.randomUUID().toString());
    assertEquals(201, mixed.statusCode());
    String mixedId = HttpTestSupport.json(mixed).at("/data/id").asText();
    assertEquals(200, send(adminToken, "POST", "/api/v1/assessments/" + mixedId + "/publish", "{\"version\":1}", java.util.UUID.randomUUID().toString()).statusCode());

    // 学生：两道程序题 ACCEPTED（满分 20+30），客观题答 1 对 1 错（5+0）。
    for (String problemId : new String[] {problemAId, problemBId}) {
      assertEquals(201, send(studentToken, "POST", "/api/v1/assessments/" + mixedId + "/submissions",
          "{\"problemId\":\"%s\",\"language\":\"java\",\"code\":\"class Main { accepted marker }\"}".formatted(problemId),
          java.util.UUID.randomUUID().toString()).statusCode());
    }
    send(studentToken, "PUT", "/api/v1/assessments/" + mixedId + "/answer-sheet",
        "{\"answers\":{\"3\":\"A\",\"4\":\"B\"}}", null);
    assertEquals(200, send(studentToken, "POST", "/api/v1/assessments/" + mixedId + "/answer-sheet/submit", "{}",
        java.util.UUID.randomUUID().toString()).statusCode());
    // 等两道程序题判分完成。
    for (int i = 0; i < 40; i++) {
      Integer done = jdbc.queryForObject(
          "SELECT COUNT(*) FROM assessment_result r JOIN assessment_submission s ON s.id=r.submission_id"
              + " WHERE s.assessment_id=? AND r.result_status='COMPLETE'", Integer.class, Long.parseLong(mixedId));
      if (done != null && done == 2) break;
      Thread.sleep(250);
    }

    var scores = send(leaderToken, "GET", "/api/v1/assessments/" + mixedId + "/member-scores?page=1&size=20", null, null);
    assertEquals(200, scores.statusCode());
    var row = HttpTestSupport.json(scores).at("/data/items/0");
    assertEquals("成员10", row.at("/memberName").asText());
    assertEquals(4, row.at("/itemCount").asInt());
    assertEquals(4, row.at("/answeredCount").asInt());
    assertEquals(4, row.at("/completedCount").asInt());
    assertEquals(0, row.at("/pendingCount").asInt());
    assertEquals(55, row.at("/totalScore").asInt());
    assertEquals(60, row.at("/maxScore").asInt());
    assertEquals(92, row.at("/scoreRate").asInt());

    // 未作答学生：全零、得分率 0。
    var idle = send(leaderToken, "GET", "/api/v1/assessments/" + mixedId + "/member-scores?page=1&size=20", null, null);
    var items = HttpTestSupport.json(idle).at("/data/items");
    for (int i = 0; i < items.size(); i++) {
      if ("成员12".equals(items.path(i).at("/memberName").asText())) {
        assertEquals(0, items.path(i).at("/totalScore").asInt());
        assertEquals(0, items.path(i).at("/answeredCount").asInt());
        assertEquals(0, items.path(i).at("/scoreRate").asInt());
      }
    }
  }

  @Test
  void legacyProgramOnlyMemberScoresKeepOldTotals() throws Exception {
    submit(studentToken, "class Main { accepted marker }");
    awaitResults(1);
    var scores = send(leaderToken, "GET", "/api/v1/assessments/" + assessmentId + "/member-scores?page=1&size=20", null, null);
    assertEquals(200, scores.statusCode());
    var row = HttpTestSupport.json(scores).at("/data/items/0");
    // 纯程序考核（迁移回填分值 100）：总分与旧口径逐分相等。
    assertEquals(1, row.at("/itemCount").asInt());
    assertEquals(100, row.at("/totalScore").asInt());
    assertEquals(100, row.at("/maxScore").asInt());
    assertEquals(100, row.at("/scoreRate").asInt());
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
