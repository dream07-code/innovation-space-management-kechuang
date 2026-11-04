package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** V16：填空人工阅卷与成绩发布字段。 */
@Testcontainers
class QuizManualGradingSchemaIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @Test
  void v16AddsManualReviewAndPublicationColumns() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      assertTrue(columns(statement, "assessment").containsAll(
          List.of("results_published_at", "results_published_by")));
      assertTrue(columns(statement, "assessment_answer_sheet_item").containsAll(
          List.of(
              "auto_awarded", "manual_score", "review_comment", "reviewed_by", "reviewed_at",
              "version")));

      // 新插入的明细：自动分默认 0、版本默认 1、人工分空
      try (var result =
          statement.executeQuery(
              "SELECT column_default FROM information_schema.columns"
                  + " WHERE table_schema=DATABASE() AND table_name='assessment_answer_sheet_item'"
                  + " AND column_name='auto_awarded'")) {
        result.next();
        assertEquals("0", result.getString(1));
      }
    }
  }

  private static List<String> columns(java.sql.Statement statement, String table)
      throws Exception {
    var names = new ArrayList<String>();
    try (var result =
        statement.executeQuery(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='"
                + table
                + "'")) {
      while (result.next()) names.add(result.getString(1).toLowerCase());
    }
    return names;
  }
}
