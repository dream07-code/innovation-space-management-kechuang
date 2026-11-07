package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.SessionService;
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

/** 填空人工阅卷：权限、状态、批量评分、版本冲突、幂等、撤销、发布与积分时机。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.auth.initial-password=synthetic-initial")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class QuizManualGradingHttpIT {
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
  String otherLeaderToken;
  String studentToken;
  String secondStudentToken;
  String assessmentId;
  String questionId;
  long firstSheetId;
  long secondSheetId;

  @BeforeEach
  void fixture() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    for (String table :
        new String[] {
          "assessment_audit", "assessment_answer_sheet_item", "assessment_answer_sheet",
              "assessment_item", "quiz_question", "assessment_result", "assessment_review",
              "ai_evaluation", "oj_test_case_result", "oj_judge_result", "assessment_submission",
              "assessment_problem", "assessment", "point_ledger", "leader_direction_scope",
              "auth_session", "api_idempotency", "audit_log", "app_user", "member"
        }) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(11, "DIRECTION_LEADER", 1);
    seedMember(12, "STUDENT", 2);
    seedMember(13, "DIRECTION_LEADER", 2);
    seedMember(14, "STUDENT", 1);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(13,2,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    otherLeaderToken = sessions.issue(13).accessToken();
    studentToken = sessions.issue(10).accessToken();
    secondStudentToken = sessions.issue(14).accessToken();

    var created = send(adminToken, "POST", "/api/v1/quiz-questions",
        "{\"type\":\"FILL_BLANK\",\"stem\":\"写一个方法\",\"answer\":[\"foo\",\"bar\"],\"analysis\":\"参考解析\"}",
        UUID.randomUUID().toString());
    assertEquals(201, created.statusCode());
    questionId = HttpTestSupport.json(created).path("data").path("id").asText();

    var body =
        "{\"title\":\"填空考核\",\"description\":\"x\",\"directionId\":\"1\","
            + "\"startAt\":\"%s\",\"endAt\":\"%s\",\"allowedLanguages\":[\"java\"],"
            + "\"pointsEnabled\":true,"
            + "\"items\":[{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":10}]}"
                .formatted(OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2), questionId);
    var assessment =
        send(adminToken, "POST", "/api/v1/assessments", body, UUID.randomUUID().toString());
    assertEquals(201, assessment.statusCode());
    assessmentId = HttpTestSupport.json(assessment).path("data").path("id").asText();
    assertEquals(200, command("publish", 1).statusCode());

    submit(studentToken, "public int sum(int a, int b) {\n  return a + b;\n}");
    submit(secondStudentToken, "foo");
    closeAssessment();
    firstSheetId = sheetId(10);
    secondSheetId = sheetId(14);
  }

  @Test
  void summaryCarriesFillBlankQuestionsWithFullStem() throws Exception {
    // 阅卷页依赖一次请求拿到题目清单与完整题干，避免逐题回查导致长时间空白。
    var response =
        send(
            adminToken,
            "GET",
            "/api/v1/assessments/" + assessmentId + "/quiz-review/summary",
            "",
            null);
    assertEquals(200, response.statusCode());
    var data = HttpTestSupport.json(response).path("data");
    assertEquals("填空考核", data.path("assessment").path("title").asText());
    assertEquals(1, data.path("questions").size());
    var question = data.path("questions").path(0);
    assertEquals(questionId, question.path("questionId").asText());
    assertEquals("写一个方法", question.path("stem").asText());
    assertEquals(10, question.path("score").asInt());
    assertEquals(2, question.path("referenceAnswers").size());
    assertEquals("参考解析", question.path("analysis").asText());
  }

  @Test
  void permissionsStateAndBatchValidation() throws Exception {
    assertEquals(
        404,
        send(studentToken, "GET", reviewPath("all"), "", null).statusCode());
    assertEquals(
        404,
        send(otherLeaderToken, "GET", reviewPath("all"), "", null).statusCode());
    assertEquals(200, send(leaderToken, "GET", reviewPath("all"), "", null).statusCode());

    var preview =
        HttpTestSupport.json(send(adminToken, "GET", reviewPath("all"), "", null)).path("data");
    assertEquals(10, preview.path("question").path("score").asInt());
    assertEquals(2, preview.path("items").size());
    assertEquals(0, preview.path("items").path(0).path("autoAwarded").asInt());

    // 分值越界、非本场题、未作答之外的非法位置
    assertEquals(400, grade(adminToken, firstSheetId, 3, 11, 1).statusCode());
    assertEquals(400, grade(adminToken, firstSheetId, 3, -1, 1).statusCode());

    // 版本冲突：第一次成功，第二次用旧版本
    assertEquals(200, grade(adminToken, firstSheetId, 3, 8, 1).statusCode());
    var stale = grade(adminToken, firstSheetId, 3, 5, 1);
    assertEquals(409, stale.statusCode());
    assertEquals(
        8,
        jdbc.queryForObject(
            "SELECT awarded FROM assessment_answer_sheet_item WHERE sheet_id=? AND position=3",
            Integer.class,
            firstSheetId));

    // 幂等重放：同键不重复写审计
    String key = UUID.randomUUID().toString();
    int auditsBefore =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_audit WHERE action='QUIZ_ITEM_MANUAL_GRADED'"
                + " AND resource_id=?",
            Integer.class,
            firstSheetId);
    var body =
        "{\"items\":[{\"sheetId\":\"%d\",\"position\":3,\"score\":7,\"comment\":\"ok\",\"version\":2}]}"
            .formatted(firstSheetId);
    assertEquals(200, send(adminToken, "POST",
        "/api/v1/assessments/" + assessmentId + "/quiz-review/scores", body, key).statusCode());
    assertEquals(200, send(adminToken, "POST",
        "/api/v1/assessments/" + assessmentId + "/quiz-review/scores", body, key).statusCode());
    assertEquals(
        auditsBefore + 1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_audit WHERE action='QUIZ_ITEM_MANUAL_GRADED'"
                + " AND resource_id=?",
            Integer.class,
            firstSheetId));

    // 撤销回退到自动分
    var revoke =
        send(adminToken, "POST",
            "/api/v1/assessments/" + assessmentId + "/quiz-review/scores/" + firstSheetId
                + "/3/revoke",
            "{\"version\":3}", UUID.randomUUID().toString());
    assertEquals(200, revoke.statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT awarded FROM assessment_answer_sheet_item WHERE sheet_id=? AND position=3",
            Integer.class,
            firstSheetId));
    // 已撤销（manual_score 为空）再次撤销 -> 400，不能靠撤销把其他题型分数清零。
    assertEquals(
        400,
        send(adminToken, "POST",
                "/api/v1/assessments/" + assessmentId + "/quiz-review/scores/" + firstSheetId
                    + "/3/revoke",
                "{\"version\":4}", UUID.randomUUID().toString())
            .statusCode());
  }

  @Test
  void publishRequiresAllGradedThenRevealsResultsAndSettlesPoints() throws Exception {
    // 未批完：拒绝发布
    assertEquals(409, publish().statusCode());

    assertEquals(200, grade(adminToken, firstSheetId, 3, 8, 1).statusCode());
    // 仍未批第二份
    assertEquals(409, publish().statusCode());
    assertEquals(200, grade(adminToken, secondSheetId, 3, 6, 1).statusCode());

    // 发布前学生看不到分数与明细
    var before =
        HttpTestSupport.json(
                send(studentToken, "GET",
                    "/api/v1/assessments/" + assessmentId + "/my-answer-sheet", "", null))
            .path("data");
    assertFalse(before.path("resultsPublished").asBoolean(true));
    assertTrue(before.path("totalScore").isNull());

    assertEquals(200, publish().statusCode());
    // 积分按最终分结算且只发一次
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id=10",
            Integer.class));
    assertEquals(409, publish().statusCode());

    // 发布后可见、阅卷锁定
    var after =
        HttpTestSupport.json(
                send(studentToken, "GET",
                    "/api/v1/assessments/" + assessmentId + "/my-answer-sheet", "", null))
            .path("data");
    assertTrue(after.path("resultsPublished").asBoolean());
    assertEquals(8, after.path("totalScore").asInt());
    assertEquals(409, grade(adminToken, firstSheetId, 3, 9, 4).statusCode());
  }

  // -- helpers ---------------------------------------------------------

  private void seedMember(long id, String role, long directionId) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,"
            + " membership_started_at,version,created_at,updated_at)"
            + " VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id,
        "S" + id,
        directionId,
        "学生" + id);
    jdbc.update(
        "INSERT INTO app_user(member_id,account,password_hash,role,must_change_password,status,"
            + " display_name,created_at,updated_at) VALUES(?,?,?,'" + role + "',false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id,
        "S" + id,
        "hash",
        "学生" + id);
  }

  private void submit(String token, String answer) throws Exception {
    var draft =
        send(token, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
            "{\"answers\":{\"1\":" + quote(answer) + "}}", null);
    assertEquals(200, draft.statusCode());
    assertEquals(
        200,
        send(token, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}",
                UUID.randomUUID().toString())
            .statusCode());
  }

  private void closeAssessment() throws Exception {
    var version =
        jdbc.queryForObject(
            "SELECT version FROM assessment WHERE id=?", Long.class, assessmentId);
    assertEquals(200, command("close", version).statusCode());
  }

  private HttpResponse<String> command(String action, long version) throws Exception {
    return send(adminToken, "POST",
        "/api/v1/assessments/" + assessmentId + "/" + action,
        "{\"version\":" + version + "}", UUID.randomUUID().toString());
  }

  private HttpResponse<String> publish() throws Exception {
    var version =
        jdbc.queryForObject(
            "SELECT version FROM assessment WHERE id=?", Long.class, assessmentId);
    return send(adminToken, "POST",
        "/api/v1/assessments/" + assessmentId + "/publish-results",
        "{\"version\":" + version + "}", UUID.randomUUID().toString());
  }

  private HttpResponse<String> grade(
      String token, long sheetId, int position, int score, long version) throws Exception {
    return send(token, "POST",
        "/api/v1/assessments/" + assessmentId + "/quiz-review/scores",
        "{\"items\":[{\"sheetId\":\"%d\",\"position\":%d,\"score\":%d,\"comment\":\"\",\"version\":%d}]}"
            .formatted(sheetId, position, score, version),
        UUID.randomUUID().toString());
  }

  private String reviewPath(String filter) {
    return "/api/v1/assessments/" + assessmentId + "/quiz-review?questionId=" + questionId
        + "&filter=" + filter;
  }

  private long sheetId(long memberId) {
    return jdbc.queryForObject(
        "SELECT id FROM assessment_answer_sheet WHERE assessment_id=? AND member_id=?",
        Long.class,
        assessmentId,
        memberId);
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private HttpResponse<String> send(String token, String method, String path, String body, String key)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Content-Type", "application/json")
        .timeout(java.time.Duration.ofSeconds(10));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (key != null) builder.header("Idempotency-Key", key);
    if ("GET".equals(method)) builder.GET();
    else builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
