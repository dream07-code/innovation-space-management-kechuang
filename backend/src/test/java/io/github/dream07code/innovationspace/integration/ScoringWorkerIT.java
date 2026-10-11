package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.points.PointLedgerService;
import io.github.dream07code.innovationspace.report.ReportService;
import io.github.dream07code.innovationspace.report.ReportType;
import io.github.dream07code.innovationspace.scoring.*;
import io.github.dream07code.innovationspace.security.PasswordService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.util.List;
import java.time.Clock;
import java.util.Map;
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
    var created = reports.create(Map.of("learningContent", "完成实验", "gains", "掌握接口"), principal, "11111111-1111-4111-8111-111111111111", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal);
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
    reports.create(Map.of("learningContent", "旧版本", "gains", "旧内容"), principal, "22222222-2222-4222-8222-222222222222", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal);
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
    reports.create(Map.of("learningContent", "失败评分", "gains", "保留提交"), principal, "33333333-3333-4333-8333-333333333333", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal);
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
    var failing = new ScoringWorker(jdbc, registry, points, properties, Clock.systemUTC(), transactions);
    assertTrue(failing.processOne());
    assertEquals("REVIEW_REQUIRED", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertEquals("TIMEOUT", jdbc.queryForObject("SELECT safe_error_code FROM report_scoring_task", String.class));
    assertEquals(1, points.total(10));
  }

  @Test
  void manualScoreWinsOverLateAiResult() {
    SessionPrincipal principal = new SessionPrincipal(10, 10L, "STUDENT", List.of(), false, 1, 1);
    reports.create(Map.of("learningContent", "人工评分", "gains", "保留人工结果"), principal, "44444444-4444-4444-8444-444444444444", ReportType.DAILY);
    long id = jdbc.queryForObject("SELECT id FROM report", Long.class);
    reports.submit(id, 1, principal);
    jdbc.update("UPDATE report SET score_status='MANUAL',final_score=2,score_reason='人工确认' WHERE id=?", id);
    assertTrue(worker.processOne());
    assertEquals("STALE", jdbc.queryForObject("SELECT status FROM report_scoring_task", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT final_score FROM report", Integer.class));
    assertEquals(1, points.total(10));
  }
}
