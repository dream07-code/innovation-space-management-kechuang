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

/** 试卷/答题卡端点：paper 不泄答案、交卷统一判分、CLOSED 前学生不见对错。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.auth.initial-password=synthetic-initial")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class QuizAnswerSheetHttpIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Autowired io.github.dream07code.innovationspace.service.AnswerSheetService answerSheets;
  @Value("${local.server.port}") int port;
  String adminToken;
  String leaderToken;
  String studentToken;
  String otherStudentToken;
  String assessmentId;

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
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    studentToken = sessions.issue(10).accessToken();
    otherStudentToken = sessions.issue(12).accessToken();

    // 四道客观题 + 一场窗口开放的已发布考核（5+5+5+5 分）。
    String single =
        quiz("{\"type\":\"SINGLE_CHOICE\",\"stem\":\"单选题\",\"options\":[\"甲\",\"乙\",\"丙\"],\"answer\":\"B\",\"analysis\":\"解析单选\"}");
    String multi =
        quiz("{\"type\":\"MULTI_CHOICE\",\"stem\":\"多选题\",\"options\":[\"A1\",\"B1\",\"C1\"],\"answer\":[\"A\",\"C\"]}");
    String trueFalse = quiz("{\"type\":\"TRUE_FALSE\",\"stem\":\"判断题\",\"answer\":true}");
    String fill =
        quiz("{\"type\":\"FILL_BLANK\",\"stem\":\"填空题\",\"answer\":[\"自平衡二叉搜索\",\"自平衡\"],\"analysis\":\"解析填空\"}");
    var body =
        "{\"title\":\"混合考核\",\"description\":\"x\",\"directionId\":\"1\",\"startAt\":\"%s\",\"endAt\":\"%s\",\"allowedLanguages\":[\"java\"],\"items\":[{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5},{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5},{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5},{\"type\":\"QUIZ\",\"refId\":\"%s\",\"score\":5}]}"
            .formatted(
                OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusHours(2),
                single,
                multi,
                trueFalse,
                fill);
    var created = send(adminToken, "POST", "/api/v1/assessments", body, UUID.randomUUID().toString());
    assertEquals(201, created.statusCode());
    assessmentId = HttpTestSupport.json(created).path("data").path("id").asText();
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish",
                "{\"version\":1}", UUID.randomUUID().toString())
            .statusCode());
  }

  private String quiz(String body) throws Exception {
    var response = send(adminToken, "POST", "/api/v1/quiz-questions", body, UUID.randomUUID().toString());
    assertEquals(201, response.statusCode());
    return HttpTestSupport.json(response).path("data").path("id").asText();
  }

  @Test
  void paperHidesAnswersAndAnalysisAndRequiresPublishedOwnDirection() throws Exception {
    var paper = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/paper", "", null);
    assertEquals(200, paper.statusCode());
    var data = HttpTestSupport.json(paper).path("data");
    assertEquals(4, data.path("items").size());
    assertEquals("SINGLE_CHOICE", data.path("items").path(0).path("quizType").asText());
    // 响应全文不得出现答案与解析（键与值都不允许）。
    assertFalse(paper.body().contains("\"answer\""));
    assertFalse(paper.body().contains("\"analysis\""));
    assertFalse(paper.body().contains("解析"));
    // 他人方向 / 未发布考核：按不存在处理。
    assertEquals(404, send(otherStudentToken, "GET", "/api/v1/assessments/" + assessmentId + "/paper", "", null).statusCode());
  }

  @Test
  void draftCanBeSavedOutsideWindowRejectedAndLockedAfterSubmit() throws Exception {
    var draft =
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
            "{\"answers\":{\"1\":\"B\",\"3\":true}}", null);
    assertEquals(200, draft.statusCode());
    assertEquals("DRAFT", HttpTestSupport.json(draft).path("data").path("status").asText());

    // 未知条目 / 形态不匹配拒绝。
    assertEquals(
        400,
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
                "{\"answers\":{\"99\":\"B\"}}", null)
            .statusCode());
    assertEquals(
        400,
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
                "{\"answers\":{\"3\":\"对\"}}", null)
            .statusCode());

    // 窗口外（截止后）保存与交卷都拒绝。
    jdbc.update(
        "UPDATE assessment SET end_at=UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id=" + assessmentId);
    assertEquals(
        409,
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
                "{\"answers\":{\"1\":\"A\"}}", null)
            .statusCode());
    assertEquals(
        409,
        send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}",
                UUID.randomUUID().toString())
            .statusCode());
    jdbc.update(
        "UPDATE assessment SET end_at=UTC_TIMESTAMP(6) + INTERVAL 2 HOUR WHERE id=" + assessmentId);

    // 交卷判分后：保存与换键重交都拒绝，同键重放返回原结果。
    String key = UUID.randomUUID().toString();
    var submitted =
        send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}", key);
    assertEquals(200, submitted.statusCode());
    var replay =
        send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}", key);
    assertEquals(200, replay.statusCode());
    assertEquals(
        HttpTestSupport.json(submitted).path("data").path("totalScore").toString(),
        HttpTestSupport.json(replay).path("data").path("totalScore").toString());
    assertEquals(
        409,
        send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}",
                UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        409,
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
                "{\"answers\":{\"1\":\"A\"}}", null)
            .statusCode());
  }

  @Test
  void submitGradesMixedPaperAndStaffSeesCorrectnessImmediately() throws Exception {
    // 单选答对 B；多选漏选（答 A，正确 A+C）；判断未答；填空答“ 自平衡 ”（trim 命中）。
    assertEquals(
        200,
        send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
                "{\"answers\":{\"1\":\"B\",\"2\":[\"A\"],\"4\":\" 自平衡 \"}}", null)
            .statusCode());
    var submitted =
        send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}",
            UUID.randomUUID().toString());
    assertEquals(200, submitted.statusCode());
    var data = HttpTestSupport.json(submitted).path("data");
    assertEquals(3, data.path("answeredCount").asInt());
    assertEquals("SUBMITTED", data.path("status").asText());
    // 成绩未发布前，学生拿不到分数与明细。
    assertFalse(data.path("resultsPublished").asBoolean());
    assertTrue(data.path("totalScore").isNull());
    assertTrue(data.path("items").isNull());

    // 工作人员下钻：立即含逐题对错、正确答案与解析。
    var sheets =
        send(leaderToken, "GET",
            "/api/v1/assessments/" + assessmentId + "/answer-sheets?memberId=10", "", null);
    assertEquals(200, sheets.statusCode());
    var row = HttpTestSupport.json(sheets).path("data").path("items").path(0);
    assertEquals("SUBMITTED", row.path("status").asText());
    assertEquals(10, row.path("totalScore").asInt());
    assertEquals(3, row.path("items").size());
    assertEquals(5, row.path("items").path(0).path("awarded").asInt());
    assertTrue(row.path("items").path(0).path("correct").asBoolean());
    // 自动分留痕：交卷判分时同步写入 autoAwarded，供人工覆盖与撤销回退。
    assertEquals(5, row.path("items").path(0).path("autoAwarded").asInt());
    assertEquals(5, row.path("items").path(2).path("autoAwarded").asInt());
    assertEquals(0, row.path("items").path(1).path("awarded").asInt());
    assertEquals("解析单选", row.path("items").path(0).path("analysis").asText());
    assertEquals("B", row.path("items").path(0).path("correctAnswer").asText());
  }

  @Test
  void studentSeesNoCorrectnessBeforePublicationAndFullDetailAfter() throws Exception {
    send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
        "{\"answers\":{\"1\":\"B\"}}", null);
    send(studentToken, "POST", "/api/v1/assessments/" + assessmentId + "/answer-sheet/submit", "{}",
        UUID.randomUUID().toString());

    var mine = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/my-answer-sheet", "", null);
    assertEquals(200, mine.statusCode());
    assertFalse(mine.body().contains("correct"));
    assertFalse(mine.body().contains("awarded"));
    assertFalse(mine.body().contains("analysis"));
    assertEquals("SUBMITTED", HttpTestSupport.json(mine).path("data").path("status").asText());

    // 关闭但未发布：仍然看不到分数。
    jdbc.update("UPDATE assessment SET status='CLOSED' WHERE id=" + assessmentId);
    var closedUnpublished =
        send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/my-answer-sheet", "", null);
    assertTrue(closedUnpublished.body().contains("\"totalScore\":null"));
    assertFalse(closedUnpublished.body().contains("correctAnswer"));

    // 发布成绩后本人明细完整下发（含解析与正确答案）。
    jdbc.update("UPDATE assessment SET results_published_at=UTC_TIMESTAMP(6) WHERE id=" + assessmentId);
    var closed = send(studentToken, "GET", "/api/v1/assessments/" + assessmentId + "/my-answer-sheet", "", null);
    assertTrue(closed.body().contains("correctAnswer"));
    assertTrue(closed.body().contains("解析单选"));

    // 学生只能看本人；他人 memberId 一律 404。
    assertEquals(
        404,
        send(studentToken, "GET",
                "/api/v1/assessments/" + assessmentId + "/answer-sheets?memberId=12", "", null)
            .statusCode());
  }


  @Test
  void closingAssessmentForceSubmitsDraftSheets() throws Exception {
    send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
        "{\"answers\":{\"1\":\"B\"}}", null);
    // 提前关闭 = 提前截止：在批草稿自动交卷判分，不留在批学生被清零。
    var closed = send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/close",
        "{\"version\":2}", UUID.randomUUID().toString());
    assertEquals(200, closed.statusCode());
    var row = jdbc.queryForMap(
        "SELECT status,total_score FROM assessment_answer_sheet WHERE assessment_id=? AND member_id=10",
        Long.parseLong(assessmentId));
    assertEquals("SUBMITTED", row.get("status"));
    assertEquals(5, ((Number) row.get("total_score")).intValue());
  }

  @Test
  void sweepSubmitsExpiredDraftSheetsOnly() throws Exception {
    send(studentToken, "PUT", "/api/v1/assessments/" + assessmentId + "/answer-sheet",
        "{\"answers\":{\"1\":\"A\"}}", null);
    // 窗口未结束：sweep 不动它。
    assertEquals(0, answerSheets.submitExpiredDrafts());
    // 截止后：sweep 补交判分。
    jdbc.update(
        "UPDATE assessment SET end_at=UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id=" + assessmentId);
    assertEquals(1, answerSheets.submitExpiredDrafts());
    var row = jdbc.queryForMap(
        "SELECT status,total_score FROM assessment_answer_sheet WHERE assessment_id=? AND member_id=10",
        Long.parseLong(assessmentId));
    assertEquals("SUBMITTED", row.get("status"));
    assertEquals(0, ((Number) row.get("total_score")).intValue());
  }

  private void seedMember(int id, String role, int directionId) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, directionId, "成员" + id);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
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
          request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
