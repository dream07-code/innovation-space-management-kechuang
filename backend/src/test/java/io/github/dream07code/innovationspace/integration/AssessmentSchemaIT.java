package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AssessmentSchemaIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @Test
  void v6CreatesAssessmentDomainWithoutPointLedgerCoupling() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
         var statement = connection.createStatement()) {
      for (var table : new String[] {"assessment","assessment_problem","assessment_problem_assessment_test_case","assessment_problem_practice_test_case","assessment_submission","oj_test_case_result","oj_judge_result","ai_evaluation","assessment_review","assessment_result","assessment_audit"}) {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'")) {
          result.next();
          assertEquals(1, result.getInt(1), table);
        }
      }
      try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='point_ledger'")) {
        result.next();
        assertEquals(1, result.getInt(1));
      }
      try (var result = statement.executeQuery("SELECT COUNT(*) FROM assessment_audit")) {
        result.next();
        assertEquals(0, result.getInt(1));
      }
    }
  }
}
