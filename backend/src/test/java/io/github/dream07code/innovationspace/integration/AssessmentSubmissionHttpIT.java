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
    for (String table : new String[] {"assessment_audit","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result","assessment_submission","assessment_answer_sheet_item","assessment_answer_sheet","assessment_item","quiz_question","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
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

    // 存量 STDIO 题目（新建已收敛为 FUNCTION，此处用夹具保留 STDIO 判题链路覆盖）。
    problemId =
        OjFixtureSupport.seedStdioProblem(
            jdbc,
            "提交题",
            "[\"java\",\"python\"]",
            java.util.List.of(
                new String[] {"assessment-hidden-1", "A1"},
                new String[] {"assessment-hidden-2", "A2"}),
            java.util.List.of(
                new String[] {"practice-visible-1", "P1"},
                new String[] {"practice-visible-2", "P2"}));

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"提交考核","description":"x","directionId":"1","startAt":"%s","endAt":"%s","allowedLanguages":["java","python"],"items":[{"type":"PROGRAM","refId":"%s","score":100}]}
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
    assertEquals("成员10", HttpTestSupport.json(created).at("/data/memberName").asText());
    assertEquals("MOCK10", HttpTestSupport.json(created).at("/data/memberStudentNo").asText());
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
    assertEquals(2, HttpTestSupport.json(detail).at("/data/judgeResult/testCaseResults").size());
    assertEquals(1, HttpTestSupport.json(detail).at("/data/judgeResult/testCaseResults/0/position").asInt());
    assertEquals("ACCEPTED", HttpTestSupport.json(detail).at("/data/judgeResult/testCaseResults/1/verdict").asText());
    var judge = HttpTestSupport.json(detail).at("/data/judgeResult");
    // 逐测试点列表仍然只给判定摘要，不暴露输入与输出
    assertFalse(judge.at("/testCaseResults/0").has("input"));
    assertFalse(judge.at("/testCaseResults/0").has("expectedOutput"));
    // 只额外提供一个参考测试点，供学生对照修改
    assertTrue(judge.at("/sampleCase").has("position"));
    assertTrue(judge.at("/sampleCase").has("input"));
    assertTrue(judge.at("/sampleCase").has("expectedOutput"));
    assertFalse(detail.body().contains("stdout"));
    assertFalse(detail.body().contains("stderr"));
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

    var code = send(studentToken, "GET", "/api/v1/assessment-submissions/" + id + "/code", null, null);
    assertEquals(200, code.statusCode());
    ContractAssertions.response("GET", "/api/v1/assessment-submissions/" + id + "/code", code);
    assertTrue(HttpTestSupport.json(code).at("/data/code").asText().contains("private solution marker"));
    assertEquals("java", HttpTestSupport.json(code).at("/data/language").asText());
    // 其他方向的学生不能读取代码
    assertEquals(404, send(otherStudentToken, "GET", "/api/v1/assessment-submissions/" + id + "/code", null, null).statusCode());
    // 管理员可以读取，且查看操作留下审计
    assertEquals(200, send(adminToken, "GET", "/api/v1/assessment-submissions/" + id + "/code", null, null).statusCode());
    assertTrue(
        jdbc.queryForList(
                "SELECT action FROM assessment_audit WHERE resource_type='SUBMISSION' AND resource_id=? AND action='SUBMISSION_CODE_VIEWED'",
                id)
            .size()
            >= 1);
  }

  @Test
  void studentsCanReadOnlyPracticeTestCases() throws Exception {
    var practice = send(studentToken, "GET", "/api/v1/assessment-problems/" + problemId + "/practice-test-cases", "", null);
    assertEquals(200, practice.statusCode());
    ContractAssertions.response("GET", "/api/v1/assessment-problems/" + problemId + "/practice-test-cases", practice);
    assertEquals(2, HttpTestSupport.json(practice).at("/data/practiceTestCases").size());
    assertEquals("practice-visible-1", HttpTestSupport.json(practice).at("/data/practiceTestCases/0/input").asText());
    assertEquals("P2", HttpTestSupport.json(practice).at("/data/practiceTestCases/1/expectedOutput").asText());
    assertFalse(practice.body().contains("assessment-hidden-1"));

    assertEquals(403, send(studentToken, "GET", "/api/v1/assessment-problems/" + problemId + "/test-cases", "", null).statusCode());
    assertEquals(403, send(adminToken, "GET", "/api/v1/assessment-problems/" + problemId + "/practice-test-cases", "", null).statusCode());
  }

  @Test
  void practiceRunUsesOnlyPracticeTestCasesAndReturnsVisibleDetails() throws Exception {
    var run = send(studentToken, "POST", "/api/v1/assessment-problems/" + problemId + "/practice-run",
        "{\"language\":\"java\",\"code\":\"class Main { /* practice marker */ }\"}",
        "11111111-2222-4333-8444-555555555555");
    assertEquals(200, run.statusCode());
    ContractAssertions.response("POST", "/api/v1/assessment-problems/" + problemId + "/practice-run", run);
    assertEquals("ACCEPTED", HttpTestSupport.json(run).at("/data/verdict").asText());
    assertEquals(2, HttpTestSupport.json(run).at("/data/totalTestCases").asInt());
    assertEquals(2, HttpTestSupport.json(run).at("/data/testCaseResults").size());
    assertEquals("practice-visible-1", HttpTestSupport.json(run).at("/data/testCaseResults/0/input").asText());
    assertEquals("P1", HttpTestSupport.json(run).at("/data/testCaseResults/0/expectedOutput").asText());
    assertFalse(run.body().contains("assessment-hidden-1"));
    assertFalse(run.body().contains("practice marker"));
    assertFalse(run.body().contains("stdout"));
    assertFalse(run.body().contains("stderr"));

    assertEquals(403, send(adminToken, "POST", "/api/v1/assessment-problems/" + problemId + "/practice-run",
        "{\"language\":\"java\",\"code\":\"class Main{}\"}", UUID.randomUUID().toString()).statusCode());
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

  @Test
  void closedAssessmentRejectsSubmissionWithExplicitCode() throws Exception {
    var closed =
        send(
            adminToken,
            "POST",
            "/api/v1/assessments/" + assessmentId + "/close",
            "{\"version\":2}",
            UUID.randomUUID().toString());
    assertEquals(200, closed.statusCode());

    String body = """
        {"problemId":"%s","language":"java","code":"class Main{}"}
        """.formatted(problemId);

    var rejected =
        send(
            studentToken,
            "POST",
            "/api/v1/assessments/" + assessmentId + "/submissions",
            body,
            UUID.randomUUID().toString());
    // 考核已关闭就不再是「资源不存在」：学生要能看出是考核关了，而不是链接错了。
    assertEquals(409, rejected.statusCode());
    assertEquals("ASSESSMENT_CLOSED", HttpTestSupport.json(rejected).at("/code").asText());
    assertTrue(HttpTestSupport.json(rejected).at("/data").isNull());

    // 不存在的考核仍然是 404，本次只改「已关闭」这一种情况。
    var missing =
        send(
            studentToken,
            "POST",
            "/api/v1/assessments/99999999/submissions",
            body,
            UUID.randomUUID().toString());
    assertEquals(404, missing.statusCode());
    assertEquals("NOT_FOUND", HttpTestSupport.json(missing).at("/code").asText());
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
