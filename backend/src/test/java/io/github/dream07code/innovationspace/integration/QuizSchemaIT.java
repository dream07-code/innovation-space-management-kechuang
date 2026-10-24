package io.github.dream07code.innovationspace.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.testsupport.MysqlTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V12 混合试卷迁移：客观题/考核条目/答题卡四张新表、link 表下线，
 * 以及 assessment_problem_link → assessment_item(score=100) 的数据搬迁。
 */
@Testcontainers
class QuizSchemaIT {
  @Container static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

  private Connection open() throws SQLException {
    return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }

  private int tableCount(Statement statement, String table) throws SQLException {
    try (var result =
        statement.executeQuery(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"
                + table
                + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  @Test
  void v12CreatesQuizTablesAndDropsLinkTable() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection = open(); var statement = connection.createStatement()) {
      for (var table :
          new String[] {
            "quiz_question", "assessment_item", "assessment_answer_sheet",
                "assessment_answer_sheet_item"
          }) {
        assertEquals(1, tableCount(statement, table), table + " 应存在");
      }
      assertEquals(0, tableCount(statement, "assessment_problem_link"), "旧挂题表应被删除");
    }
  }

  @Test
  void v12MigratesLegacyLinksIntoItemsWithScore100() throws Exception {
    // 先迁到 V11，按旧结构种一条挂题关系，再放行 V12 断言搬迁结果。
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .target("11")
        .load()
        .migrate();
    try (var connection = open(); var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO app_user(account,password_hash,role,display_name,created_at,updated_at)"
              + " VALUES('t-admin','x','COLLEGE_ADMIN','管理员','2026-10-24 08:00:00','2026-10-24 08:00:00')");
      long userId = lastId(statement);
      statement.execute(
          "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,"
              + "examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,"
              + "status,version,created_by,created_at,updated_at)"
              + " VALUES('A+B','sum','desc','in','out','[]','EASY','[]',1000,65536,'[\"java\"]',"
              + "'PUBLISHED',1," + userId + ",'2026-10-24 08:00:00','2026-10-24 08:00:00')");
      long problemId = lastId(statement);
      statement.execute("SELECT id FROM direction LIMIT 1");
      long directionId;
      try (var result = statement.getResultSet()) {
        result.next();
        directionId = result.getLong(1);
      }
      statement.execute(
          "INSERT INTO assessment(title,description,direction_id,status,start_at,end_at,"
              + "allowed_languages,version,created_by,created_at,updated_at)"
              + " VALUES('旧考核','desc'," + directionId + ",'DRAFT','2026-10-24 08:00:00',"
              + "'2026-10-25 08:00:00','[\"java\"]',1," + userId + ","
              + "'2026-10-24 08:00:00','2026-10-24 08:00:00')");
      long assessmentId = lastId(statement);
      statement.execute(
          "INSERT INTO assessment_problem_link(assessment_id,problem_id,position) VALUES("
              + assessmentId + "," + problemId + ",1)");
    }
    MysqlTestSupport.migrate(MYSQL);
    try (var connection = open(); var statement = connection.createStatement()) {
      try (var result =
          statement.executeQuery(
              "SELECT item_type,problem_id,question_id,score FROM assessment_item"
                  + " WHERE assessment_id=(SELECT MAX(id) FROM assessment) AND position=1")) {
        assertTrue(result.next(), "旧挂题关系应搬入 assessment_item");
        assertEquals("PROGRAM", result.getString(1));
        assertEquals(100, result.getInt(4), "迁移分值默认 100（旧口径每题满分不变）");
        assertEquals(0, result.getObject(3) == null ? 0 : 1, "question_id 应为空");
      }
    }
  }

  @Test
  void v12EnforcesItemUniquenessAndExactlyOneReference() throws Exception {
    MysqlTestSupport.migrate(MYSQL);
    try (var connection = open(); var statement = connection.createStatement()) {
      seedOneAssessmentWithProgramItem(statement);
      // 同一考核重复挂同一程序题：唯一约束拒绝。
      assertThrows(
          SQLException.class,
          () ->
              statement.execute(
                  "INSERT INTO assessment_item(assessment_id,position,item_type,problem_id,score)"
                      + " VALUES((SELECT MAX(id) FROM assessment),2,'PROGRAM',"
                      + "(SELECT problem_id FROM assessment_item LIMIT 1),50)"));
      // 两个引用同非空：CHECK 拒绝。
      assertThrows(
          SQLException.class,
          () ->
              statement.execute(
                  "INSERT INTO assessment_item(assessment_id,position,item_type,problem_id,"
                      + "question_id,score) VALUES((SELECT MAX(id) FROM assessment),3,'PROGRAM',1,1,50)"));
      // 分值越界：CHECK 拒绝。
      assertThrows(
          SQLException.class,
          () ->
              statement.execute(
                  "INSERT INTO assessment_item(assessment_id,position,item_type,question_id,score)"
                      + " VALUES((SELECT MAX(id) FROM assessment),4,'QUIZ',1,0)"));
    }
  }

  private void seedOneAssessmentWithProgramItem(Statement statement) throws SQLException {
    statement.execute(
        "INSERT INTO app_user(account,password_hash,role,display_name,created_at,updated_at)"
            + " VALUES('t-admin2','x','COLLEGE_ADMIN','管理员','2026-10-24 08:00:00','2026-10-24 08:00:00')");
    long userId = lastId(statement);
    statement.execute(
        "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,"
            + "examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,"
            + "status,version,created_by,created_at,updated_at)"
            + " VALUES('B+A','sum','desc','in','out','[]','EASY','[]',1000,65536,'[\"java\"]',"
            + "'PUBLISHED',1," + userId + ",'2026-10-24 08:00:00','2026-10-24 08:00:00')");
    long problemId = lastId(statement);
    statement.execute("SELECT id FROM direction LIMIT 1");
    long directionId;
    try (var result = statement.getResultSet()) {
      result.next();
      directionId = result.getLong(1);
    }
    statement.execute(
        "INSERT INTO assessment(title,description,direction_id,status,start_at,end_at,"
            + "allowed_languages,version,created_by,created_at,updated_at)"
            + " VALUES('约束考核','desc'," + directionId + ",'DRAFT','2026-10-24 08:00:00',"
            + "'2026-10-25 08:00:00','[\"java\"]',1," + userId + ","
            + "'2026-10-24 08:00:00','2026-10-24 08:00:00')");
    long assessmentId = lastId(statement);
    statement.execute(
        "INSERT INTO assessment_item(assessment_id,position,item_type,problem_id,score) VALUES("
            + assessmentId + ",1,'PROGRAM'," + problemId + ",100)");
  }

  private long lastId(Statement statement) throws SQLException {
    try (var result = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
      result.next();
      return result.getLong(1);
    }
  }
}
