package io.github.dream07code.innovationspace.integration;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * V17：AI 助教多会话与学习画像。
 *
 * <p>只新增表，既有表结构不动；删会话时消息必须跟着删，不留孤儿行。
 */
@Testcontainers
class TutorSchemaIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  @Test
  void v17AddsTutorTablesWithoutTouchingExistingOnes() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      assertTrue(columns(statement, "tutor_conversation").containsAll(
          List.of("id", "member_id", "title", "problem_id", "created_at", "updated_at")));
      assertTrue(columns(statement, "tutor_message").containsAll(
          List.of("id", "conversation_id", "role", "content", "created_at")));
      assertTrue(columns(statement, "tutor_learning_profile").containsAll(
          List.of(
              "member_id", "attempted_problems", "passed_problems", "pass_rate",
              "submission_count", "average_score", "last_submitted_at", "weak_points",
              "recommendations", "snapshot_at")));

      // 删学生/删题目都不该被助教数据挡住：会话与画像随学生删除，题目只解除关联。
      assertEquals("CASCADE", deleteRule(statement, "fk_tutor_conversation_member"));
      assertEquals("CASCADE", deleteRule(statement, "fk_tutor_profile_member"));
      assertEquals("CASCADE", deleteRule(statement, "fk_tutor_message_conversation"));
      assertEquals("SET NULL", deleteRule(statement, "fk_tutor_conversation_problem"));

      // 外键级联：删会话后消息不残留。
      statement.executeUpdate(
          "INSERT INTO direction(code,name,enabled,created_at,updated_at)"
              + " VALUES('T17','测试方向',1,NOW(6),NOW(6))");
      statement.executeUpdate(
          "INSERT INTO member(student_no,direction_id,name,membership_status,joined_at,"
              + "membership_started_at,version,created_at,updated_at)"
              + " VALUES('T17-1',(SELECT id FROM direction WHERE code='T17'),'测试学生','ACTIVE',"
              + "NOW(6),NOW(6),1,NOW(6),NOW(6))");
      long memberId = idOf(statement, "SELECT id FROM member WHERE student_no='T17-1'");
      statement.executeUpdate(
          "INSERT INTO tutor_conversation(member_id,title,problem_id,created_at,updated_at)"
              + " VALUES(" + memberId + ",'会话',NULL,NOW(6),NOW(6))");
      long conversationId =
          idOf(statement, "SELECT id FROM tutor_conversation WHERE member_id=" + memberId);
      statement.executeUpdate(
          "INSERT INTO tutor_message(conversation_id,role,content,created_at)"
              + " VALUES(" + conversationId + ",'user','问题',NOW(6))");
      statement.executeUpdate("DELETE FROM tutor_conversation WHERE id=" + conversationId);
      try (var result =
          statement.executeQuery(
              "SELECT COUNT(*) FROM tutor_message WHERE conversation_id=" + conversationId)) {
        result.next();
        assertEquals(0, result.getInt(1));
      }
    }
  }

  private static long idOf(Statement statement, String sql) throws Exception {
    try (var result = statement.executeQuery(sql)) {
      assertTrue(result.next(), sql);
      return result.getLong(1);
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
                + "'")) {
      while (result.next()) names.add(result.getString(1).toLowerCase());
    }
    return names;
  }
}
