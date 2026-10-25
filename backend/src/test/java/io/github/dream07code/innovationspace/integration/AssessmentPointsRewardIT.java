package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.security.SessionService;
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

/**
 * 考核积分奖励结算：close 时按 totalScore 比例发放，第一名 20 分封顶。
 * 规则详见 docs/superpowers/specs/2026-10-26-assessment-points-reward-design.md。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.membership.auto-removal-enabled=false",
      "app.auth.initial-password=synthetic-initial"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class AssessmentPointsRewardIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  @Value("${local.server.port}") int port;
  String adminToken;
  String studentToken;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table :
        new String[] {
          "assessment_audit",
          "point_ledger",
          "report_scoring_task",
          "assessment_result",
          "assessment_submission",
          "assessment_answer_sheet_item",
          "assessment_answer_sheet",
          "assessment_item",
          "assessment_problem",
          "quiz_question",
          "assessment",
          "api_idempotency",
          "leader_direction_scope",
          "auth_session",
          "audit_log",
          "app_user",
          "member"
        }) {
      jdbc.update("DELETE FROM " + table);
    }
    // 方向 1 的三个学生：成绩 120 / 96 / 60（120 为最高分）。
    seedMember(10, 120);
    seedMember(11, 96);
    seedMember(12, 60);
    // 方向 2 的学生：不应领到方向 1 考核的积分。
    seedMember(13, 200);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(20,NULL,'synthetic-admin','hash','COLLEGE_ADMIN',false,'ENABLED','管理员',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    adminToken = sessions.issue(20).accessToken();
    studentToken = sessions.issue(10).accessToken();
  }

  /** 创建积分考核并给学生成员种一条 final_score 的最新提交成绩。 */
  private String createAssessment(boolean pointsEnabled, String problemId) throws Exception {
    var created =
        send(adminToken, "POST", "/api/v1/assessments",
            "{\"title\":\"积分考核\",\"description\":\"x\",\"directionId\":\"1\","
                + "\"startAt\":\"2026-10-20T09:00:00+08:00\",\"endAt\":\"2026-10-21T09:00:00+08:00\","
                + "\"allowedLanguages\":[\"java\"],\"items\":[{\"type\":\"PROGRAM\",\"refId\":\"" + problemId + "\",\"score\":100}]"
                + (pointsEnabled ? ",\"pointsEnabled\":true" : "")
                + "}",
                java.util.UUID.randomUUID().toString());
    assertEquals(201, created.statusCode());
    return HttpTestSupport.json(created).path("data").path("id").asText();
  }

  private long seedProgramScore(long assessmentId, long problemId, long memberId, int finalScore) {
    jdbc.update(
        "INSERT INTO assessment_submission(id,assessment_id,problem_id,member_id,language,status,submitted_at,updated_at)"
            + " VALUES(?, ?, ?, ?, 'java','JUDGED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        9000 + memberId, assessmentId, problemId, memberId);
    jdbc.update(
        "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,time_ms,memory_kb,human_score,final_score,score_source,result_status,updated_at)"
            + " VALUES(?, 'ACCEPTED', 1, 1, 10, 1024, ?, ?, 'HUMAN', 'COMPLETE', UTC_TIMESTAMP(6))",
        9000 + memberId, finalScore, finalScore);
    return 9000 + memberId;
  }

  @Test
  void closeSettlesRewardByRatioWithTopTwenty() throws Exception {
    var problem =
        send(adminToken, "POST", "/api/v1/assessment-problems",
            "{\"title\":\"积分题\",\"summary\":\"s\",\"description\":\"d\",\"inputFormat\":\"无\",\"outputFormat\":\"和\",\"examples\":[],\"difficulty\":\"EASY\",\"tags\":[],\"timeLimitMs\":1000,\"memoryLimitKb\":65536,\"allowedLanguages\":[\"java\"],\"functionName\":\"add\",\"parameterTypes\":[\"int\",\"int\"],\"returnType\":\"int\",\"assessmentTestCases\":[{\"input\":\"[1,2]\",\"expectedOutput\":\"3\"}]}",
            "aaaaaaaa-2222-4222-8222-222222222222");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();

    String assessmentId = createAssessment(true, problemId);
    seedProgramScore(Long.parseLong(assessmentId), Long.parseLong(problemId), 10, 120);
    seedProgramScore(Long.parseLong(assessmentId), Long.parseLong(problemId), 11, 96);
    seedProgramScore(Long.parseLong(assessmentId), Long.parseLong(problemId), 12, 60);

    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish",
                "{\"version\":1}", "aaaaaaaa-3333-4333-8333-333333333333")
            .statusCode());
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/close",
                "{\"version\":2}", "aaaaaaaa-4444-4444-8444-444444444444")
            .statusCode());

    // 比例：120→20、96→16、60→10；方向 2 的学生（未参加）不发。
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD'", Integer.class));
    assertEquals(
        20,
        jdbc.queryForObject(
            "SELECT delta FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id=10",
            Integer.class));
    assertEquals(
        16,
        jdbc.queryForObject(
            "SELECT delta FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id=11",
            Integer.class));
    assertEquals(
        10,
        jdbc.queryForObject(
            "SELECT delta FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id=12",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id=13",
            Integer.class));

    // 学生积分记录可见（含来源）。
    var ledger = send(studentToken, "GET", "/api/v1/points/ledger?memberId=10", "", null);
    assertEquals(200, ledger.statusCode());
    assertEquals(
        "ASSESSMENT_REWARD",
        HttpTestSupport.json(ledger).at("/data/items/0/source").asText());
  }

  @Test
  void closeWithoutSwitchOrAllZeroScoresGrantsNothing() throws Exception {
    var problem =
        send(adminToken, "POST", "/api/v1/assessment-problems",
            "{\"title\":\"无积分题\",\"summary\":\"s\",\"description\":\"d\",\"inputFormat\":\"无\",\"outputFormat\":\"和\",\"examples\":[],\"difficulty\":\"EASY\",\"tags\":[],\"timeLimitMs\":1000,\"memoryLimitKb\":65536,\"allowedLanguages\":[\"java\"],\"functionName\":\"add\",\"parameterTypes\":[\"int\",\"int\"],\"returnType\":\"int\",\"assessmentTestCases\":[{\"input\":\"[1,2]\",\"expectedOutput\":\"3\"}]}",
            "aaaaaaaa-5555-4555-8555-555555555555");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();

    // 开关缺省（false）：有成绩也不发。
    String offId = createAssessment(false, problemId);
    seedProgramScore(Long.parseLong(offId), Long.parseLong(problemId), 10, 120);
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + offId + "/publish",
                "{\"version\":1}", "aaaaaaaa-6666-4666-8666-666666666666")
            .statusCode());
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + offId + "/close",
                "{\"version\":2}", "aaaaaaaa-7777-4777-8777-777777777777")
            .statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD'", Integer.class));

    // 开关开启但全员 0 成绩：整场不发。
    String zeroId = createAssessment(true, problemId);
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + zeroId + "/publish",
                "{\"version\":1}", "aaaaaaaa-8888-4888-8888-888888888888")
            .statusCode());
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + zeroId + "/close",
                "{\"version\":2}", "aaaaaaaa-9999-4999-8999-999999999999")
            .statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD'", Integer.class));
  }

  @Test
  void tiedTopScoresBothGetTwentyAndReplayDoesNotDuplicate() throws Exception {
    var problem =
        send(adminToken, "POST", "/api/v1/assessment-problems",
            "{\"title\":\"并列题\",\"summary\":\"s\",\"description\":\"d\",\"inputFormat\":\"无\",\"outputFormat\":\"和\",\"examples\":[],\"difficulty\":\"EASY\",\"tags\":[],\"timeLimitMs\":1000,\"memoryLimitKb\":65536,\"allowedLanguages\":[\"java\"],\"functionName\":\"add\",\"parameterTypes\":[\"int\",\"int\"],\"returnType\":\"int\",\"assessmentTestCases\":[{\"input\":\"[1,2]\",\"expectedOutput\":\"3\"}]}",
            "aaaaaaaa-abab-4bab-8bab-babababababa");
    assertEquals(201, problem.statusCode());
    String problemId = HttpTestSupport.json(problem).path("data").path("id").asText();

    String assessmentId = createAssessment(true, problemId);
    seedProgramScore(Long.parseLong(assessmentId), Long.parseLong(problemId), 10, 100);
    seedProgramScore(Long.parseLong(assessmentId), Long.parseLong(problemId), 11, 100);

    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/publish",
                "{\"version\":1}", "aaaaaaaa-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            .statusCode());
    assertEquals(
        200,
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/close",
                "{\"version\":2}", "aaaaaaaa-cccc-4ccc-8ccc-cccccccccccc")
            .statusCode());

    // 并列第一各拿 20 分。
    for (long member : new long[] {10, 11}) {
      assertEquals(
          20,
          jdbc.queryForObject(
              "SELECT delta FROM point_ledger WHERE source='ASSESSMENT_REWARD' AND member_id="
                  + member,
              Integer.class));
    }

    // 幂等键重放 close：响应一致且不重复发分。
    var replay =
        send(adminToken, "POST", "/api/v1/assessments/" + assessmentId + "/close",
            "{\"version\":2}", "aaaaaaaa-cccc-4ccc-8ccc-cccccccccccc");
    assertEquals(200, replay.statusCode());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_ledger WHERE source='ASSESSMENT_REWARD'", Integer.class));
  }

  private void seedMember(int id, int unusedScore) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, id == 13 ? 2 : 1, "成员" + id);
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash','STUDENT',false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, "成员" + id);
  }

  private HttpResponse<String> send(
      String token, String method, String path, String body, String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var builder =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Content-Type", "application/json");
      if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
      if (key != null) builder.header("Idempotency-Key", key);
      return client.send(
          builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
