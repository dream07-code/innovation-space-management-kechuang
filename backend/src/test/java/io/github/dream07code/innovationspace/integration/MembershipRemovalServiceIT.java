package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.innovationspace.membership.MembershipRemovalService;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.OwnedDataSourceConfiguration;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
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
      "app.membership.auto-removal-enabled=false",
      "app.auth.initial-password=synthetic-initial"
    })
@Import(OwnedDataSourceConfiguration.class)
@DirtiesContext
@Testcontainers
class MembershipRemovalServiceIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    MysqlTestSupport.registerMigratedDataSource(registry, MYSQL);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired MembershipRemovalService removals;
  @Autowired SessionService sessions;

  @BeforeEach
  void fixture() {
    MysqlTestSupport.migrate(MYSQL);
    for (String table : new String[] {"point_ledger", "report_scoring_task", "report_version", "report_review", "report", "api_idempotency", "leader_direction_scope", "auth_session", "audit_log", "app_user", "member"}) {
      jdbc.update("DELETE FROM " + table);
    }
    seedMember(10, "STUDENT", 13);
    seedMember(11, "STUDENT", 15);
    seedMember(12, "STUDENT", 30);
    seedMember(13, "STUDENT", 30);
    seedMember(14, "STUDENT", 30);
    seedMember(15, "STUDENT", 30);
    seedMember(16, "STUDENT", 30);
    seedMember(17, "DIRECTION_LEADER", 30);
    seedReport(12, "DAILY", "SUBMITTED", 15);
    seedReport(13, "DAILY", "SUBMITTED", 14);
    seedReport(14, "DAILY", "SUBMITTED", 31);
    seedReport(15, "DAILY", "DRAFT", 20);
    seedReport(16, "WEEKLY", "SUBMITTED", 20);
    jdbc.update("INSERT INTO point_ledger(member_id,delta,source,business_effective_at,recorded_at,rule_version,event_key) VALUES(11,2,'SUBMISSION_BASE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'v1','removal-original')");
    sessions.issue(11);
  }

  @Test
  void removeDueMembersUsesCompleteNaturalDaysAndOnlyStudents() {
    assertEquals(5, removals.removeDueMembers());
    assertEquals("ACTIVE", status(10));
    assertEquals("REMOVED", status(11));
    assertEquals("REMOVED", status(12));
    assertEquals("ACTIVE", status(13));
    assertEquals("REMOVED", status(14));
    assertEquals("REMOVED", status(15));
    assertEquals("REMOVED", status(16));
    assertEquals("ACTIVE", status(17));

    assertEquals("TWO_WEEKS_NO_DAILY_REPORT", jdbc.queryForObject("SELECT removal_reason FROM member WHERE id=11", String.class));
    assertNotNull(jdbc.queryForObject("SELECT removed_at FROM member WHERE id=11", Timestamp.class));
    assertTrue(jdbc.queryForObject("SELECT version FROM member WHERE id=11", Integer.class) >= 2);
    assertTrue(jdbc.queryForObject("SELECT auth_revision FROM app_user WHERE id=11", Integer.class) >= 2);
    assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE user_id=11", Timestamp.class));
    assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM point_ledger WHERE member_id=11", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id=11 AND source='REVERSAL'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MEMBER_AUTO_REMOVED' AND resource_id=11", Integer.class));
    assertEquals("TWO_WEEKS_NO_DAILY_REPORT", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(detail,'$.reasonCode')) FROM audit_log WHERE resource_id=11", String.class));

    assertEquals(0, removals.removeDueMembers());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MEMBER_AUTO_REMOVED' AND resource_id=11", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id=11 AND source='REVERSAL'", Integer.class));
  }

  private void seedMember(int id, String role, int startDaysAgo) {
    jdbc.update(
        "INSERT INTO member(id,student_no,direction_id,name,membership_status,joined_at,membership_started_at,created_at,updated_at) VALUES(?,?,1,?,'ACTIVE',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, "MOCK" + id, "成员" + id, businessTimestamp(startDaysAgo), businessTimestamp(startDaysAgo));
    jdbc.update(
        "INSERT INTO app_user(id,member_id,account,password_hash,role,must_change_password,status,display_name,created_at,updated_at) VALUES(?,?,?, 'hash',?,false,'ENABLED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
        id, id, "MOCK" + id, role, "成员" + id);
  }

  private void seedReport(int memberId, String type, String status, int submittedDaysAgo) {
    int reportId = 1000 + memberId;
    int versionId = 2000 + memberId;
    Timestamp submitted = "SUBMITTED".equals(status) ? businessTimestamp(submittedDaysAgo) : null;
    if ("DAILY".equals(type)) {
      jdbc.update(
          "INSERT INTO report(id,member_id,direction_id,report_type,report_date,status,current_version,score_status,first_submitted_at,created_at,updated_at) VALUES(?,?,1,'DAILY',?,?,1,'NOT_REQUESTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
          reportId, memberId, localDate(submittedDaysAgo), status, submitted);
    } else {
      jdbc.update(
          "INSERT INTO report(id,member_id,direction_id,report_type,week_start,week_end,status,current_version,score_status,first_submitted_at,created_at,updated_at) VALUES(?,?,1,'WEEKLY',?,?,?,1,'NOT_REQUESTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
          reportId, memberId, localDate(submittedDaysAgo), localDate(submittedDaysAgo).plusDays(6), status, submitted);
    }
    jdbc.update(
        "INSERT INTO report_version(id,report_id,version,content_json,content_hash,edited_by,created_at) VALUES(?,?,1,'{}',UNHEX(SHA2('{}',256)),?,UTC_TIMESTAMP(6))",
        versionId, reportId, memberId);
  }

  private String status(int id) {
    return jdbc.queryForObject("SELECT membership_status FROM member WHERE id=?", String.class, id);
  }

  private static LocalDate localDate(int daysAgo) {
    return LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(daysAgo);
  }

  private static Timestamp businessTimestamp(int daysAgo) {
    return Timestamp.from(localDate(daysAgo).atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
  }
}
