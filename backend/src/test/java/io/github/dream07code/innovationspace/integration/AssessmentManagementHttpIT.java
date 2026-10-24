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
class AssessmentManagementHttpIT {
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

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"assessment_audit","assessment_answer_sheet_item","assessment_answer_sheet","assessment_item","quiz_question","assessment_result","assessment_review","ai_evaluation","oj_test_case_result","oj_judge_result","assessment_submission","assessment_problem","assessment","point_ledger","leader_direction_scope","auth_session","api_idempotency","audit_log","app_user","member"}) {
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
  }

  @Test
  void adminCreatesProblemAndAssessmentThenPublishesAndCloses() throws Exception {
    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", """
        {"title":"两数之和","summary":"基础题","description":"给定数组和目标值。","inputFormat":"一行数组","outputFormat":"两个下标","examples":[{"input":"[2,7]\\n9","output":"[0,1]"}],"difficulty":"EASY","tags":["数组"],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"[2,7]\\n9","expectedOutput":"0 1"},{"input":"[3,3]\\n6","expectedOutput":"0 1"}],"practiceTestCases":[{"input":"[1,2]\\n3","expectedOutput":"0 1"}]}
        """, "11111111-1111-4111-8111-111111111111");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();
    ContractAssertions.response("POST", "/api/v1/assessment-problems", problem);
    assertFalse(problem.body().contains("expectedOutput"));
    var problemCases = send(adminToken, "GET", "/api/v1/assessment-problems/" + problemId + "/test-cases", "", null);
    assertEquals(200, problemCases.statusCode());
    assertEquals(2, HttpTestSupport.json(problemCases).path("data").path("assessmentTestCases").size());
    assertEquals(1, HttpTestSupport.json(problemCases).path("data").path("practiceTestCases").size());
    assertEquals(403, send(studentToken, "GET", "/api/v1/assessment-problems/" + problemId + "/test-cases", "", null).statusCode());

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"基础算法考核","description":"第一期考核","directionId":"1","startAt":"2026-10-12T09:00:00+08:00","endAt":"2026-10-19T09:00:00+08:00","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":%s,"score":100}]}
        """.formatted("\"" + problemId + "\""), "22222222-2222-4222-8222-222222222222");
    assertEquals(201, assessment.statusCode());
    assertEquals(201, assessment.statusCode());
    String assessmentId = HttpTestSupport.json(assessment).path("data").path("id").asText();
    assertEquals("DRAFT", HttpTestSupport.json(assessment).at("/data/status").asText());
    ContractAssertions.response("POST", "/api/v1/assessments", assessment);

    var replay = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"基础算法考核","description":"第一期考核","directionId":"1","startAt":"2026-10-12T09:00:00+08:00","endAt":"2026-10-19T09:00:00+08:00","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":%s,"score":100}]}
        """.formatted("\"" + problemId + "\""), "22222222-2222-4222-8222-222222222222");
    assertEquals(201, replay.statusCode());
    assertEquals(assessmentId, HttpTestSupport.json(replay).path("data").path("id").asText());

    var published = send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", "33333333-3333-4333-8333-333333333333");
    assertEquals(200, published.statusCode());
    assertEquals("PUBLISHED", HttpTestSupport.json(published).path("data").path("status").asText());
    var studentProblems = send(studentToken, "GET", "/api/v1/assessment-problems?page=1&size=20", "", null);
    assertEquals(200, studentProblems.statusCode());
    assertEquals(1, HttpTestSupport.json(studentProblems).path("data").path("total").asInt());
    assertEquals(problemId, HttpTestSupport.json(studentProblems).path("data").path("items").path(0).path("id").asText());
    var studentProblem = send(studentToken, "GET", "/api/v1/assessment-problems/" + problemId, "", null);
    assertEquals(200, studentProblem.statusCode());
    assertEquals("PUBLISHED", HttpTestSupport.json(studentProblem).path("data").path("status").asText());
    var closed = send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/close", "{\"version\":2}", "44444444-4444-4444-8444-444444444444");
    assertEquals(200, closed.statusCode());
    assertEquals("CLOSED", HttpTestSupport.json(closed).path("data").path("status").asText());
  }

  @Test
  void mixedItemsCanBeLinkedValidatedAndLockedByPublish() throws Exception {
    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", validImportProblem("混合条目程序题"), "a1a1a1a1-1111-4111-8111-1111111111a1");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();

    var quizBody =
        "{\"type\":\"MULTI_CHOICE\",\"stem\":\"哪些是无损编码？\",\"options\":[\"哈夫曼\",\"PCM\",\"行程\",\"DPCM\"],\"answer\":[\"A\",\"C\"]}";
    var quiz = send(adminToken, "POST", "/api/v1/quiz-questions", quizBody, "a1a1a1a1-2222-4222-8222-222222222222");
    assertEquals(201, quiz.statusCode());
    String quizId = HttpTestSupport.json(quiz).path("data").path("id").asText();

    var body =
        "{\"title\":\"混合考核\",\"description\":\"x\",\"directionId\":\"1\",\"startAt\":\"2026-10-24T09:00:00+08:00\",\"endAt\":\"2026-10-25T09:00:00+08:00\",\"allowedLanguages\":[\"java\"],\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":20},{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5}]}"
            .formatted(problemId, quizId);
    var created = send(adminToken, "POST", "/api/v1/assessments", body, "a1a1a1a1-3333-4333-8333-333333333333");
    assertEquals(201, created.statusCode());
    var data = HttpTestSupport.json(created).path("data");
    assertEquals(2, data.path("items").size());
    assertEquals("QUIZ", data.path("items").path(1).path("type").asText());
    assertEquals(5, data.path("items").path(1).path("score").asInt());
    assertEquals(1, data.path("problemIds").size());
    String assessmentId = data.path("id").asText();

    // 重复挂同一题拒绝。
    var duplicate =
        send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId,
            "{\"version\":1,\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":10},{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":10}]}"
                .formatted(problemId, problemId), null);
    assertEquals(400, duplicate.statusCode());
    // 分值越界拒绝。
    assertEquals(
        400,
        send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId,
                "{\"version\":1,\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":0}]}".formatted(problemId), null)
            .statusCode());
    // 不存在的引用拒绝。
    assertEquals(
        400,
        send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId,
                "{\"version\":1,\"items\":[{\"type\":\"QUIZ\",\"refId\":\"999999\",\"score\":5}]}", null)
            .statusCode());
    // 已停用的客观题拒绝再挂。
    jdbc.update("UPDATE quiz_question SET status='RETIRED' WHERE id=" + quizId);
    assertEquals(
        400,
        send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId,
                "{\"version\":1,\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":10},{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5}]}"
                    .formatted(problemId, quizId), null)
            .statusCode());
    jdbc.update("UPDATE quiz_question SET status='DRAFT' WHERE id=" + quizId);

    // 发布：程序题与客观题一并 DRAFT→PUBLISHED；发布后条目不可再改。
    var published =
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish", "{\"version\":1}", "a1a1a1a1-4444-4444-8444-444444444444");
    assertEquals(200, published.statusCode());
    assertEquals(
        "PUBLISHED",
        jdbc.queryForObject("SELECT status FROM quiz_question WHERE id=" + quizId, String.class));
    assertEquals(
        409,
        send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId,
                "{\"version\":2,\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"%s\",\"score\":99}]}".formatted(problemId), null)
            .statusCode());
  }

  @Test
  void managementPermissionsAndVersionConflictsAreEnforced() throws Exception {
    var studentCreate = send(studentToken, "POST", "/api/v1/assessment-problems", """
        {"title":"学生不能创建","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """, "55555555-5555-4555-8555-555555555555");
    assertEquals(403, studentCreate.statusCode());

    var problem = send(leaderToken, "POST", "/api/v1/assessment-problems", """
        {"title":"负责人题目","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """, "66666666-6666-4666-8666-666666666666");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();

    var crossDirection = send(leaderToken, "POST", "/api/v1/assessments", """
        {"title":"跨方向","description":"x","directionId":"2","startAt":"2026-10-12T09:00:00+08:00","endAt":"2026-10-13T09:00:00+08:00","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":%s,"score":100}]}
        """.formatted("\"" + problemId + "\""), "77777777-7777-4777-8777-777777777777");
    assertEquals(403, crossDirection.statusCode());

    var assessment = send(adminToken, "POST", "/api/v1/assessments", """
        {"title":"版本考核","description":"x","directionId":"1","startAt":"2026-10-12T09:00:00+08:00","endAt":"2026-10-13T09:00:00+08:00","allowedLanguages":["java"],"items":[{"type":"PROGRAM","refId":%s,"score":100}]}
        """.formatted("\"" + problemId + "\""), "88888888-8888-4888-8888-888888888888");
    assertEquals(201, assessment.statusCode());
    String assessmentId = HttpTestSupport.json(assessment).path("data").path("id").asText();
    assertEquals(409, send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId, "{\"version\":99,\"title\":\"错误版本\"}", null).statusCode());
    assertEquals(400, send(adminToken, "PATCH", "/api/v1/assessments/" + assessmentId, "{\"version\":1}", null).statusCode());
  }

  @Test
  void problemTestCasesCanBeReplacedByStaffButNotReadByStudents() throws Exception {
    var problem = send(adminToken, "POST", "/api/v1/assessment-problems", """
        {"title":"测试点替换题","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """, "99999999-9999-4999-8999-999999999999");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();
    var updated = send(adminToken, "PATCH", "/api/v1/assessment-problems/" + problemId, """
        {"version":1,"assessmentTestCases":[{"input":"2","expectedOutput":"2"},{"input":"3","expectedOutput":"3"}],"practiceTestCases":[{"input":"4","expectedOutput":"4"}]}
        """, null);
    assertEquals(200, updated.statusCode());
    assertEquals(2, HttpTestSupport.json(updated).path("data").path("version").asInt());
    var cases = send(adminToken, "GET", "/api/v1/assessment-problems/" + problemId + "/test-cases", "", null);
    assertEquals(200, cases.statusCode());
    assertEquals(2, HttpTestSupport.json(cases).path("data").path("assessmentTestCases").size());
    assertEquals("2", HttpTestSupport.json(cases).path("data").path("assessmentTestCases").path(0).path("input").asText());
    assertEquals(1, HttpTestSupport.json(cases).path("data").path("practiceTestCases").size());
    assertEquals(403, send(studentToken, "GET", "/api/v1/assessment-problems/" + problemId + "/test-cases", "", null).statusCode());
  }

  @Test
  void adminImportsProblemBatchWithIdempotencyAndSafeResult() throws Exception {
    var body = """
        {"problems":[%s,%s]}
        """.formatted(validImportProblem("批量导入甲"), validImportProblem("批量导入乙"));
    var first = send(adminToken, "POST", "/api/v1/assessment-problems/import", body, "aaaaaaaa-1111-4111-8111-111111111111");
    assertEquals(201, first.statusCode());
    assertEquals(2, HttpTestSupport.json(first).path("data").path("importedCount").asInt());
    assertEquals(2, HttpTestSupport.json(first).path("data").path("problemIds").size());
    assertEquals(0, HttpTestSupport.json(first).path("data").path("errors").size());
    assertFalse(first.body().contains("expectedOutput"));
    ContractAssertions.response("POST", "/api/v1/assessment-problems/import", first);

    var replay = send(adminToken, "POST", "/api/v1/assessment-problems/import", body, "aaaaaaaa-1111-4111-8111-111111111111");
    assertEquals(201, replay.statusCode());
    assertEquals(
        HttpTestSupport.json(first).path("data").path("problemIds").toString(),
        HttpTestSupport.json(replay).path("data").path("problemIds").toString());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Integer.class));
  }

  @Test
  void invalidProblemRollsBackEntireImportAndReturnsSafePerItemError() throws Exception {
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Integer.class);
    var body = """
        {"problems":[%s,{"title":"缺少测试点","summary":"x","description":"x","inputFormat":"x","outputFormat":"x","examples":[],"difficulty":"EASY","tags":[],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"practiceTestCases":[]}]}
        """.formatted(validImportProblem("将被回滚"));
    var failed = send(adminToken, "POST", "/api/v1/assessment-problems/import", body, "bbbbbbbb-2222-4222-8222-222222222222");
    assertEquals(400, failed.statusCode());
    assertEquals("INVALID_REQUEST", HttpTestSupport.json(failed).path("code").asText());
    assertEquals(0, HttpTestSupport.json(failed).path("data").path("importedCount").asInt());
    assertEquals(0, HttpTestSupport.json(failed).path("data").path("problemIds").size());
    assertEquals(1, HttpTestSupport.json(failed).path("data").path("errors").size());
    assertEquals(1, HttpTestSupport.json(failed).path("data").path("errors").path(0).path("index").asInt());
    assertEquals("INVALID_PROBLEM", HttpTestSupport.json(failed).path("data").path("errors").path(0).path("code").asText());
    assertFalse(failed.body().contains("缺少测试点"));
    assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Integer.class));
    ContractAssertions.response("POST", "/api/v1/assessment-problems/import", failed);
  }

  @Test
  void importRejectsDuplicateLimitsOversizeAndStudents() throws Exception {
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Integer.class);

    var duplicate = send(adminToken, "POST", "/api/v1/assessment-problems/import",
        "{\"problems\":[%s,%s]}".formatted(validImportProblem("重复标题"), validImportProblem("  重复标题  ")),
        "cccccccc-3333-4333-8333-333333333333");
    assertEquals(400, duplicate.statusCode());
    assertEquals("DUPLICATE_TITLE", HttpTestSupport.json(duplicate).path("data").path("errors").path(0).path("code").asText());

    var tooMany = send(adminToken, "POST", "/api/v1/assessment-problems/import",
        "{\"problems\":[" + java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> validImportProblem("第" + i + "题"))
            .collect(java.util.stream.Collectors.joining(",")) + "]}",
        "dddddddd-4444-4444-8444-444444444444");
    assertEquals(400, tooMany.statusCode());
    assertEquals("TOO_MANY_PROBLEMS", HttpTestSupport.json(tooMany).path("data").path("errors").path(0).path("code").asText());

    var oversized = send(adminToken, "POST", "/api/v1/assessment-problems/import",
        oversizedImportBody(), "eeeeeeee-5555-4555-8555-555555555555");
    assertEquals(400, oversized.statusCode());
    assertEquals("REQUEST_TOO_LARGE", HttpTestSupport.json(oversized).path("data").path("errors").path(0).path("code").asText());

    var student = send(studentToken, "POST", "/api/v1/assessment-problems/import",
        "{\"problems\":[%s]}".formatted(validImportProblem("学生越权")),
        "ffffffff-6666-4666-8666-666666666666");
    assertEquals(403, student.statusCode());
    assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Integer.class));
  }

  private static String validImportProblem(String title) {
    return """
        {"title":"%s","summary":"导入摘要","description":"导入题面","inputFormat":"一行输入","outputFormat":"一行输出","examples":[],"difficulty":"EASY","tags":["导入"],"timeLimitMs":1000,"memoryLimitKb":65536,"allowedLanguages":["java"],"assessmentTestCases":[{"input":"1","expectedOutput":"1"}]}
        """.formatted(title).strip();
  }

  private static String oversizedImportBody() {
    String input = "x".repeat(900_000);
    String problem =
        "{\"title\":\"超大导入题\",\"summary\":\"x\",\"description\":\"x\",\"inputFormat\":\"x\","
            + "\"outputFormat\":\"x\",\"examples\":[],\"difficulty\":\"EASY\",\"tags\":[],"
            + "\"timeLimitMs\":1000,\"memoryLimitKb\":65536,\"allowedLanguages\":[\"java\"],"
            + "\"assessmentTestCases\":[{\"input\":\"" + input + "\",\"expectedOutput\":\"ok\"}]}";
    return "{\"problems\":[" + java.util.stream.IntStream.range(0, 6)
        .mapToObj(i -> problem)
        .collect(java.util.stream.Collectors.joining(",")) + "]}";
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
      return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
