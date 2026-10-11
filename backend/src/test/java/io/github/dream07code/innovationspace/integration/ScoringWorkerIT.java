package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.points.PointLedgerService;
import io.github.dream07code.innovationspace.report.ReportService;
import io.github.dream07code.innovationspace.report.ReportType;
import io.github.dream07code.innovationspace.scoring.*;
import io.github.dream07code.innovationspace.security.PasswordService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import io.github.dream07code.innovationspace.points.PointsPolicyProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "app.report.points-policy-status=READY",
      "app.report.points-mode=FINAL_SCORE",
      "app.auth.initial-password=synthetic-initial",
      "app.ai.enabled=true",
      "app.ai.provider=mock",
      "app.ai.api-key=synthetic-not-a-real-key",
      "app.ai.model=mock-model",
      "app.ai.worker-scheduling-enabled=false"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class ScoringWorkerIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordService passwords;
  @Autowired ReportService reports;
  @Autowired ScoringWorker worker;
  @Autowired PointLedgerService points;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "report_scoring_task", "report_version", "report_review", "report", "api_idempotency", "auth_session", "audit_log", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(10,'MOCK010',1,'评分同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(10,10,'MOCK010',?,'STUDENT',false,'ENABLED','评分同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", passwords.encode("synthetic-password12"));
  }

  @Test
  void workerScoresCurrentVersionOnceAndAddsOnlyFinalScoreDelta() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    var created = reports.create(dailyRequest("完成实验", "掌握接口"), principal, "11111111-1111-4111-8111-111111111111", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE source='SUBMISSION_BASE'", Integer.class));
    assertTrue(worker.processOne());
    assertEquals(3, points.total(10));
    assertEquals(2, jdbc.queryForObject("SELECT delta FROM point_ledger WHERE source='AI_ADJUSTMENT'", Integer.class));
    assertEquals("SCORED", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertFalse(worker.processOne());
    assertEquals(3, points.total(10));
    assertNotNull(created);
  }

  @Test
  void staleVersionCannotOverwriteCurrentAndLateResultBecomesStale() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(dailyRequest("旧版本", "旧内容"), principal, "22222222-2222-4222-8222-222222222222", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    jdbc.update("UPDATE report SET editable_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", id);
    reports.update(id, Map.of("version", 1, "learningContent", "新版本", "gains", "新内容"), principal, ReportType.DAILY);
    assertTrue(worker.processOne());
    assertTrue(worker.processOne());
    var tasks = jdbc.queryForList("SELECT status FROM report_scoring_task ORDER BY id");
    assertEquals("STALE", tasks.get(0).get("status"));
    assertEquals("SCORED", tasks.get(1).get("status"));
    assertEquals(3, points.total(10));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE source='AI_ADJUSTMENT'", Integer.class));
  }

  @Test
  void providerFailureIsRetriedThenReviewRequiredWithoutZeroScore() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(dailyRequest("失败评分", "保留提交"), principal, "33333333-3333-4333-8333-333333333333", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    ScoringProperties properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("mock");
    properties.setModel("mock-model");
    properties.setApiKey("synthetic-not-a-real-key");
    properties.setMaxRetries(0);
    var registry = new ScoringProviderRegistry(properties, List.of(new MockReportScoringProvider() {
      @Override
      public ProviderScore score(ScoringInput input) {
        throw new OpenAiCompatibleReportScoringProvider.ProviderScoreException("timeout", new RuntimeException());
      }
    }));
    var failing = new ScoringWorker(jdbc, registry, points, properties, policy("FINAL_SCORE", 3), Clock.systemUTC(), transactions);
    assertTrue(failing.processOne());
    assertEquals("REVIEW_REQUIRED", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertEquals("TIMEOUT", jdbc.queryForObject("SELECT safe_error_code FROM report_scoring_task", String.class));
    assertEquals(1, points.total(10));
  }

  @Test
  void manualScoreWinsOverLateAiResult() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(dailyRequest("人工评分", "保留人工结果"), principal, "44444444-4444-4444-8444-444444444444", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    jdbc.update("UPDATE report SET score_status='MANUAL',final_score=2,score_reason='人工确认' WHERE id=?", id);
    assertThrows(ApiException.class, () -> reports.update(id, Map.of("version", 1, "learningContent", "不应修改", "gains", "人工锁定"), principal, ReportType.DAILY));
    assertTrue(worker.processOne());
    assertEquals("STALE", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT final_score FROM report", Integer.class));
    assertEquals(1, points.total(10));
  }

  @Test
  void rescoringWritesDeltaAgainstCurrentLedgerNet() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    var scoring = workerWithScores(3, 2, 1);
    reports.create(dailyRequest("第一次", "完整内容"), principal, "55555555-5555-4555-8555-555555555555", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    assertTrue(scoring.processOne());
    assertEquals(3, points.total(10));
    jdbc.update("UPDATE report SET editable_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", id);
    reports.update(id, Map.of("version", 1, "learningContent", "第二次", "gains", "降低质量"), principal, ReportType.DAILY);
    assertTrue(scoring.processOne());
    assertEquals(2, points.total(10));
    reports.update(id, Map.of("version", 2, "learningContent", "第三次", "gains", "继续降低"), principal, ReportType.DAILY);
    assertTrue(scoring.processOne());
    assertEquals(1, points.total(10));
    assertEquals(List.of(2, -1, -1), jdbc.queryForList("SELECT delta FROM point_ledger WHERE source='AI_ADJUSTMENT' ORDER BY id", Integer.class));
  }

  @Test
  void aiAdjustmentUsesFirstSubmittedBusinessTime() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    var scoring = workerWithScores(3);
    reports.create(dailyRequest("跨月评分", "月末提交"), principal, "66666666-6666-4666-8666-666666666666", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "11111111-1111-4111-8111-111111111111");
    jdbc.update("UPDATE report SET first_submitted_at='2026-09-30 15:00:00' WHERE id=?", id);
    jdbc.update("UPDATE point_ledger SET business_effective_at='2026-09-30 15:00:00' WHERE report_id=?", id);
    assertTrue(scoring.processOne());
    var base = jdbc.queryForObject("SELECT business_effective_at FROM point_ledger WHERE source='SUBMISSION_BASE'", String.class);
    var adjustment = jdbc.queryForObject("SELECT business_effective_at FROM point_ledger WHERE source='AI_ADJUSTMENT'", String.class);
    assertEquals(base, adjustment);
    assertTrue(adjustment.startsWith("2026-09-30"));
    assertEquals(3, points.total(10));
  }

  private PointsPolicyProperties policy(String mode, int maxFinalScore) {
    PointsPolicyProperties policy = new PointsPolicyProperties();
    policy.setPointsPolicyStatus("READY");
    policy.setPointsMode(mode);
    policy.setMaxFinalScore(maxFinalScore);
    policy.validate();
    return policy;
  }

  private Map<String, Object> dailyRequest(String learning, String gains) {
    return Map.of("reportDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "learningContent", learning, "gains", gains);
  }

  private ScoringWorker workerWithScores(int... scores) {
    return workerWithScores("FINAL_SCORE", 3, scores);
  }

  private ScoringWorker workerWithScores(String mode, int maxFinalScore, int... scores) {
    ScoringProperties properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("mock");
    properties.setModel("mock-model");
    properties.setApiKey("synthetic-not-a-real-key");
    var sequence = new AtomicInteger();
    var provider = new MockReportScoringProvider() {
      @Override
      public ProviderScore score(ScoringInput input) {
        int score = scores[Math.min(sequence.getAndIncrement(), scores.length - 1)];
        return new ProviderScore(score, "合成评分", "mock", "mock-model");
      }
    };
    PointsPolicyProperties policy = new PointsPolicyProperties();
    policy.setPointsPolicyStatus("READY");
    policy.setPointsMode(mode);
    policy.setMaxFinalScore(maxFinalScore);
    policy.validate();
    return new ScoringWorker(jdbc, new ScoringProviderRegistry(properties, List.of(provider)), points, properties, policy, Clock.systemUTC(), transactions);
  }

  @Test
  void expiredProcessingLeaseIsRecovered() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(dailyRequest("租约恢复", "避免卡住"), principal, "77777777-7777-4777-8777-777777777777", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "88888888-8888-4888-8888-888888888888");
    jdbc.update("UPDATE report_scoring_task SET status='PROCESSING',updated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)");
    assertTrue(worker.processOne());
    assertEquals("SCORED", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertEquals(3, points.total(10));
  }

  @Test
  void failedOldVersionCannotOverwriteNewerVersionState() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(dailyRequest("失败旧版本", "新版本已存在"), principal, "99999999-9999-4999-8999-999999999999", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    jdbc.update("UPDATE report SET editable_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", id);
    reports.update(id, Map.of("version", 1, "learningContent", "新版本", "gains", "当前有效"), principal, ReportType.DAILY);
    ScoringProperties properties = new ScoringProperties();
    properties.setEnabled(true); properties.setProvider("mock"); properties.setModel("mock-model"); properties.setApiKey("synthetic-not-a-real-key"); properties.setMaxRetries(0);
    var failing = new ScoringWorker(jdbc, new ScoringProviderRegistry(properties, List.of(new MockReportScoringProvider() {
      @Override public ProviderScore score(ScoringInput input) {
        throw new OpenAiCompatibleReportScoringProvider.ProviderScoreException("timeout", new RuntimeException());
      }
    })), points, properties, policy("FINAL_SCORE", 3), Clock.systemUTC(), transactions);
    assertTrue(failing.processOne());
    assertEquals("STALE", jdbc.queryForObject("SELECT status FROM report_scoring_task ORDER BY id LIMIT 1", String.class));
    assertEquals(1, points.total(10));
  }

  @Test
  void basePlusAiModeAddsAiScoreSeparatelyAndRescoreAdjustsOnlyAiPart() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    var scoring = workerWithScores("BASE_PLUS_AI", 4, 3, 2);
    reports.create(dailyRequest("基础加评分", "分别入账"), principal, "abababab-abab-4aba-8aba-abababababab", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal, "babababa-baba-4bab-8bab-babababababa");
    assertTrue(scoring.processOne());
    assertEquals(4, points.total(10));
    assertEquals(3, jdbc.queryForObject("SELECT delta FROM point_ledger WHERE source='AI_ADJUSTMENT'", Integer.class));
    jdbc.update("UPDATE report SET editable_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?", id);
    reports.update(id, Map.of("version", 1, "learningContent", "基础加评分二", "gains", "降低评分"), principal, ReportType.DAILY);
    assertTrue(scoring.processOne());
    assertEquals(3, points.total(10));
    assertEquals(2, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE source='AI_ADJUSTMENT'", Integer.class));
  }
}
