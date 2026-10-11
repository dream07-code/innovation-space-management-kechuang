package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.points.PointLedgerService;
import io.github.dream07code.innovationspace.scoring.*;
import io.github.dream07code.testsupport.MysqlTestSupport;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ScoringProviderIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();
  @Test
  void fakeProviderReturnsValidScoreWithoutIdentityAndLedgerAddsOnlyDelta() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var observed = new AtomicReference<String>();
    server.createContext("/v1/chat/completions", exchange -> {
      observed.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      var body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":3,\\\"reason\\\":\\\"具体学习、实践并复盘\\\"}\"}}]}";
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
      exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
    });
    server.start();
    try {
      var props = new ScoringProperties();
      props.setEnabled(true); props.setProvider("openai-compatible");
      props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      props.setApiKey("synthetic-not-a-real-key"); props.setModel("fake-model");
      var provider = new OpenAiCompatibleReportScoringProvider(props);
      var result = provider.score(new ScoringInput("DAILY", "1", "v1", "学习了安全接口"));
      assertEquals(3, result.score()); result.validate();
      assertTrue(observed.get().contains("学习了安全接口"));
      assertTrue(observed.get().contains("评1至3分"));
      assertTrue(observed.get().contains("untrusted content"));
      assertFalse(observed.get().contains("synthetic-not-a-real-key"));
      assertFalse(observed.get().contains("20260001"));
      var jdbc = new JdbcTemplate(dataSource());
      jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(5,'MOCK005',1,'合成同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
      jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(5,5,'MOCK005','hash','STUDENT',false,'ENABLED','合成同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
      jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,awarded_points,created_at,updated_at) VALUES(5,5,1,'DAILY','2026-10-11','SUBMITTED',1,'PROCESSING',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
      jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at,is_current) VALUES(5,5,1,'{}',UNHEX(SHA2('{}',256)),5,UTC_TIMESTAMP(6),TRUE)");
      jdbc.update("INSERT INTO report_scoring_task(id,report_id,report_version_id,status,attempt,created_at,updated_at) VALUES(5,5,5,'PROCESSING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
      jdbc.update("INSERT INTO point_ledger(member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(5,5,5,1,'SUBMISSION_BASE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'v1','base')");
      var points = new PointLedgerService(jdbc, Clock.systemUTC());
      points.appendIfAbsent(5,5,5,2,"AI_ADJUSTMENT","score-5-3",java.time.Instant.now());
      assertEquals(3, points.total(5));
      points.appendIfAbsent(5,5,5,2,"AI_ADJUSTMENT","score-5-3",java.time.Instant.now());
      assertEquals(3, points.total(5));
    } finally { server.stop(0); }
  }
  private static javax.sql.DataSource dataSource() throws Exception {
    var config = new com.zaxxer.hikari.HikariConfig();
    config.setJdbcUrl(MYSQL.getJdbcUrl()); config.setUsername(MYSQL.getUsername()); config.setPassword(MYSQL.getPassword());
    return new com.zaxxer.hikari.HikariDataSource(config);
  }

  @Test
  void providerRejectsMalformedOutOfBoundsAndUnavailableResponses() throws Exception {
    for (String body : new String[] {"not-json", "{\"score\":0,\"reason\":\"bad\"}", "{\"score\":4,\"reason\":\"bad\"}", "{\"score\":3}"}) {
      assertThrows(OpenAiCompatibleReportScoringProvider.ProviderScoreException.class, () -> callProvider(body, 200, 0));
    }
    assertThrows(OpenAiCompatibleReportScoringProvider.ProviderScoreException.class, () -> callProvider("{\"score\":3,\"reason\":\"ok\"}", 500, 0));
    assertThrows(OpenAiCompatibleReportScoringProvider.ProviderScoreException.class, () -> callProvider("{\"score\":3,\"reason\":\"ok\"}", 200, 1200));
  }

  private ProviderScore callProvider(String body, int status, long delayMillis) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      if (delayMillis > 0) {
        try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    });
    server.start();
    try {
      var props = new ScoringProperties();
      props.setEnabled(true); props.setProvider("openai-compatible");
      props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      props.setApiKey("synthetic-not-a-real-key"); props.setModel("fake-model"); props.setRequestTimeoutMs(1000);
      return new OpenAiCompatibleReportScoringProvider(props).score(new ScoringInput("DAILY", "1", "v1", "synthetic"));
    } finally { server.stop(0); }
  }
}
