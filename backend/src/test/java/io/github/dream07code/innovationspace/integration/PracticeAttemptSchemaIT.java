package io.github.dream07code.innovationspace.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V18：题库练习的运行记录。
 *
 * <p>这张表只为学习画像统计活动指标服务，必须与考核链路互不影响：删学生/删题目要能级联，
 * 通过点数不能超过总点数（否则画像算通过率时会拿到不可能的数据）。
 */
@Testcontainers
class PracticeAttemptSchemaIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @Test
  void v18RecordsPracticeAttemptsWithoutOwningScores() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      assertEquals(
          List.of(
              "id",
              "problem_id",
              "member_id",
              "language",
              "verdict",
              "passed_test_cases",
              "total_test_cases",
              "created_at"),
          columns(statement, "problem_practice_attempt"),
          "列顺序即建表顺序，多一列少一列都要显式改这里");

      assertEquals("CASCADE", deleteRule(statement, "fk_practice_attempt_member"));
      assertEquals("CASCADE", deleteRule(statement, "fk_practice_attempt_problem"));

      // 练习记录不挂成绩：这张表不引用 assessment_result / assessment_submission。
      try (var result =
          statement.executeQuery(
              "SELECT COUNT(*) FROM information_schema.referential_constraints"
                  + " WHERE constraint_schema=DATABASE()"
                  + " AND table_name='problem_practice_attempt'"
                  + " AND referenced_table_name<>'member'"
                  + " AND referenced_table_name<>'assessment_problem'")) {
        result.next();
        assertEquals(0, result.getInt(1));
      }
    }
  }

  private static String deleteRule(Statement statement, String constraint) throws Exception {
    try (var result =
        statement.executeQuery(
            "SELECT delete_rule FROM information_schema.referential_constraints"
                + " WHERE constraint_schema=DATABASE() AND constraint_name='"
                + constraint
                + "'")) {
      assertTrue(result.next(), constraint);
      return result.getString(1);
    }
  }

  private static List<String> columns(Statement statement, String table) throws Exception {
    var names = new ArrayList<String>();
    try (var result =
        statement.executeQuery(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='"
                + table
                + "' ORDER BY ordinal_position")) {
      while (result.next()) names.add(result.getString(1).toLowerCase());
    }
    return names;
  }
}
