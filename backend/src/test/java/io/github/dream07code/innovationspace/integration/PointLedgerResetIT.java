package io.github.dream07code.innovationspace.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dream07code.innovationspace.membership.InactivityPolicy;
import io.github.dream07code.innovationspace.points.PointLedgerService;
import io.github.dream07code.testsupport.MysqlTestSupport;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PointLedgerResetIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @Test
  void zeroesEveryNetWithoutDeletingHistory() {
    MysqlTestSupport.migrate(MYSQL);
    var jdbc = new JdbcTemplate(dataSource());
    jdbc.update("INSERT INTO member(id,student_no,direction_id,name,joined_at,membership_started_at,created_at,updated_at) VALUES(10,'MOCK010',1,'合成同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(10,10,'MOCK010','hash','STUDENT',false,'ENABLED','合成同学',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,created_at,updated_at) VALUES(20,10,1,'DAILY','2026-10-10','SUBMITTED',1,'SCORED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO report(id,member_id,direction_id,report_type,week_start,week_end,status,current_version,score_status,created_at,updated_at) VALUES(21,10,1,'WEEKLY','2026-10-05','2026-10-11','SUBMITTED',1,'SCORED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(30,20,1,'{}',UNHEX(SHA2('{}',256)),10,UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(31,21,1,'{}',UNHEX(SHA2('{}',256)),10,UTC_TIMESTAMP(6))");
    insertLedger(jdbc, 40, 20, 30, 2, "2026-09-30 15:00:00", "original-40");
    insertLedger(jdbc, 41, 20, 30, 1, "2026-10-01 01:00:00", "original-41");
    insertLedger(jdbc, 42, 21, 31, 3, "2026-10-02 01:00:00", "original-42");
    jdbc.update("INSERT INTO point_ledger(id,member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(43,10,20,30,2,'AI_ADJUSTMENT','2026-10-02 02:00:00','2026-10-02 02:00:00','v1','original-43')");

    var points = new PointLedgerService(jdbc, Clock.systemUTC());
    assertEquals(4, points.reverseNonZeroEntries(10, InactivityPolicy.RULE_VERSION, Instant.parse("2026-10-16T00:00:00Z")));
    assertEquals(0, points.reverseNonZeroEntries(10, InactivityPolicy.RULE_VERSION, Instant.parse("2026-10-17T00:00:00Z")));

    assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE source<>'REVERSAL'", Integer.class));
    assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE source='REVERSAL'", Integer.class));
    assertEquals(0, points.sourceNet(10, "AI_ADJUSTMENT"));
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=10", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=20", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE report_id=21", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE business_effective_at<'2026-10-01 16:00:00'", Integer.class));
  }

  private static void insertLedger(
      JdbcTemplate jdbc, long id, long reportId, long versionId, int delta, String effectiveAt, String eventKey) {
    jdbc.update(
        "INSERT INTO point_ledger(id,member_id,report_id,report_version_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(?,?,?, ?,?,'SUBMISSION_BASE',?,?,'v1',?)",
        id, 10, reportId, versionId, delta, effectiveAt, effectiveAt, eventKey);
  }

  private static DataSource dataSource() {
    var config = new HikariConfig();
    config.setJdbcUrl(MYSQL.getJdbcUrl());
    config.setUsername(MYSQL.getUsername());
    config.setPassword(MYSQL.getPassword());
    return new HikariDataSource(config);
  }
}
