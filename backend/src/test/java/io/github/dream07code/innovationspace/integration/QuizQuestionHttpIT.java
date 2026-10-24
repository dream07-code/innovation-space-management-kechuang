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

/** 客观题库端点：仅负责人/管理员可见（学生一律 404），四题型校验与生命周期。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.auth.initial-password=synthetic-initial")
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class QuizQuestionHttpIT {
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

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table :
        new String[] {
          "assessment_audit", "assessment_answer_sheet_item", "assessment_answer_sheet",
              "assessment_item", "quiz_question", "auth_session", "api_idempotency",
              "audit_log", "leader_direction_scope", "app_user", "member"
        }) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 1);
    seedMember(11, "DIRECTION_LEADER", 1);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at)"
            + " VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update(
        "INSERT INTO leader_direction_scope(user_id,direction_id,granted_at) VALUES(11,1,UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    leaderToken = sessions.issue(11).accessToken();
    studentToken = sessions.issue(10).accessToken();
  }

  @Test
  void studentGets404OnEveryQuizBankOperation() throws Exception {
    assertEquals(404, send(studentToken, "GET", "/api/v1/quiz-questions", "", null).statusCode());
    assertEquals(
        404,
        send(studentToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "key-student-1")
            .statusCode());
    assertEquals(404, send(studentToken, "GET", "/api/v1/quiz-questions/1", "", null).statusCode());
    assertEquals(
        404, send(studentToken, "POST", "/api/v1/quiz-questions/1/retire", "{}", "key-student-2")
            .statusCode());
  }

  @Test
  void createValidatesEachTypeAndStoresAnswer() throws Exception {
    var created = send(adminToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "aaaa1111-1111-4111-8111-111111111111");
    assertEquals(201, created.statusCode());
    assertEquals("SINGLE_CHOICE", HttpTestSupport.json(created).path("data").path("type").asText());
    assertEquals("C", HttpTestSupport.json(created).path("data").path("answer").asText());
    assertEquals(3, HttpTestSupport.json(created).path("data").path("options").size());
    ContractAssertions.response("POST", "/api/v1/quiz-questions", created);
    String id = HttpTestSupport.json(created).path("data").path("id").asText();

    // 同键重放回放首次结果。
    var replay = send(adminToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "aaaa1111-1111-4111-8111-111111111111");
    assertEquals(201, replay.statusCode());
    assertEquals(id, HttpTestSupport.json(replay).path("data").path("id").asText());

    var multi =
        send(
            adminToken,
            "POST",
            "/api/v1/quiz-questions",
            """
            {"type":"MULTI_CHOICE","stem":"哪些是无损编码？","options":["哈夫曼","PCM","行程","DPCM"],"answer":["A","C"]}
            """,
            "aaaa2222-2222-4222-8222-222222222222");
    assertEquals(201, multi.statusCode());

    var trueFalse =
        send(
            adminToken,
            "POST",
            "/api/v1/quiz-questions",
            """
            {"type":"TRUE_FALSE","stem":"空串即空白串。","answer":false}
            """,
            "aaaa3333-3333-4333-8333-333333333333");
    assertEquals(201, trueFalse.statusCode());
    assertFalse(HttpTestSupport.json(trueFalse).path("data").path("answer").asBoolean());

    var fill =
        send(
            adminToken,
            "POST",
            "/api/v1/quiz-questions",
            """
            {"type":"FILL_BLANK","stem":"AVL 是____树。","answer":["自平衡二叉搜索","自平衡"]}
            """,
            "aaaa4444-4444-4444-8444-444444444444");
    assertEquals(201, fill.statusCode());
    assertEquals(2, HttpTestSupport.json(fill).path("data").path("answer").size());

    // 非法载荷逐一拒绝。
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"越界\",\"options\":[\"a\",\"b\",\"c\"],\"answer\":\"D\"}",
                "bbbb1111-1111-4111-8111-111111111111")
            .statusCode());
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"MULTI_CHOICE\",\"stem\":\"重复字母\",\"options\":[\"a\",\"b\"],\"answer\":[\"A\",\"A\"]}",
                "bbbb2222-2222-4222-8222-222222222222")
            .statusCode());
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"TRUE_FALSE\",\"stem\":\"带选项\",\"options\":[\"对\",\"错\"],\"answer\":true}",
                "bbbb3333-3333-4333-8333-333333333333")
            .statusCode());
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"FILL_BLANK\",\"stem\":\"空备选\",\"answer\":[\" \"]}",
                "bbbb4444-4444-4444-8444-444444444444")
            .statusCode());
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"选项不足\",\"options\":[\"a\"],\"answer\":\"A\"}",
                "bbbb5555-5555-4555-8555-555555555555")
            .statusCode());
    assertEquals(
        400,
        send(
                adminToken,
                "POST",
                "/api/v1/quiz-questions",
                "{\"type\":\"SINGLE_CHOICE\",\"stem\":\"  \",\"options\":[\"a\",\"b\"],\"answer\":\"A\"}",
                "bbbb6666-6666-4666-8666-666666666666")
            .statusCode());
  }

  @Test
  void leaderSeesAllQuizQuestionsAndFilters() throws Exception {
    assertEquals(
        201,
        send(leaderToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "cccc1111-1111-4111-8111-111111111111")
            .statusCode());
    var list = send(leaderToken, "GET", "/api/v1/quiz-questions?type=SINGLE_CHOICE&keyword=二叉树", "", null);
    assertEquals(200, list.statusCode());
    assertEquals(1, HttpTestSupport.json(list).path("data").path("items").size());
    assertEquals(
        0,
        HttpTestSupport.json(
                send(leaderToken, "GET", "/api/v1/quiz-questions?type=FILL_BLANK", "", null))
            .path("data")
            .path("items")
            .size());
  }

  @Test
  void publishedQuestionRejectsEditButRetireIsAllowed() throws Exception {
    var created = send(adminToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "dddd1111-1111-4111-8111-111111111111");
    String id = HttpTestSupport.json(created).path("data").path("id").asText();
    jdbc.update("UPDATE quiz_question SET status='PUBLISHED' WHERE id=" + id);

    assertEquals(
        409,
        send(adminToken, "PATCH", "/api/v1/quiz-questions/" + id, "{\"stem\":\"改题干\"}", null)
            .statusCode());
    assertEquals(
        201,
        send(adminToken, "POST", "/api/v1/quiz-questions/" + id + "/retire", "{}", "dddd2222-2222-4222-8222-222222222222")
            .statusCode());

    var retiredList = send(adminToken, "GET", "/api/v1/quiz-questions", "", null);
    assertEquals(0, HttpTestSupport.json(retiredList).path("data").path("items").size());
    var explicit = send(adminToken, "GET", "/api/v1/quiz-questions?status=RETIRED", "", null);
    assertEquals(1, HttpTestSupport.json(explicit).path("data").path("items").size());
  }

  @Test
  void draftQuestionCanBeEditedWithinType() throws Exception {
    var created = send(adminToken, "POST", "/api/v1/quiz-questions", singleChoiceBody(), "eeee1111-1111-4111-8111-111111111111");
    String id = HttpTestSupport.json(created).path("data").path("id").asText();

    var updated =
        send(adminToken, "PATCH", "/api/v1/quiz-questions/" + id, "{\"stem\":\"新题干\",\"answer\":\"A\"}", null);
    assertEquals(200, updated.statusCode());
    assertEquals("新题干", HttpTestSupport.json(updated).path("data").path("stem").asText());
    assertEquals("A", HttpTestSupport.json(updated).path("data").path("answer").asText());

    assertEquals(
        400,
        send(adminToken, "PATCH", "/api/v1/quiz-questions/" + id, "{\"type\":\"FILL_BLANK\"}", null)
            .statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_audit WHERE action='QUIZ_QUESTION_CREATED'", Integer.class));
  }

  private static String singleChoiceBody() {
    return """
        {"type":"SINGLE_CHOICE","stem":"二叉树第 k 层最多多少个结点？","options":["2k","2^(k-1)","2^(k+1)"],"answer":"C","analysis":"满二叉树性质","tags":["树","二叉树"]}
        """;
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
