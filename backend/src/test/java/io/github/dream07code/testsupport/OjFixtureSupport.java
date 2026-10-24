package io.github.dream07code.testsupport;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OJ 集测夹具：直接插入存量 STDIO 题目。
 *
 * 新建题目已收敛为函数传参（FUNCTION）模式，命令行读写只作为历史数据保留；
 * 判题/提交/计分等集成测试沿用 STDIO 语义的部分用本夹具模拟存量题目。
 */
public final class OjFixtureSupport {
  private OjFixtureSupport() {}

  /** 插入 DRAFT 状态的 STDIO 题目（created_by 固定为各集测夹具的管理员用户 20）。 */
  public static String seedStdioProblem(
      JdbcTemplate jdbc,
      String title,
      String languagesJson,
      List<String[]> assessmentCases,
      List<String[]> practiceCases) {
    jdbc.update(
        "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,judge_mode,status,version,created_by,created_at,updated_at)"
            + " VALUES(?, 'x', 'x', 'x', 'x', '[]', 'EASY', '[]', 1000, 65536, ?, 'STDIO', 'DRAFT', 1, 20, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
        title,
        languagesJson);
    long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    insertCases(jdbc, "assessment_problem_assessment_test_case", id, assessmentCases);
    insertCases(jdbc, "assessment_problem_practice_test_case", id, practiceCases);
    return String.valueOf(id);
  }

  private static void insertCases(
      JdbcTemplate jdbc, String table, long problemId, List<String[]> cases) {
    int position = 1;
    for (String[] testCase : cases) {
      jdbc.update(
          "INSERT INTO "
              + table
              + "(problem_id,position,input,expected_output,created_at,updated_at)"
              + " VALUES(?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
          problemId,
          position++,
          testCase[0],
          testCase[1]);
    }
  }
}
