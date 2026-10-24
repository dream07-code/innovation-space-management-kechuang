package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * OJ 考核计分。
 *
 * <p>不再调用任何 AI 模型：自动分直接按通过的测试点比例计算
 * （{@code round(通过数 / 总数 * 100)}），人工评分可随时覆盖自动分。
 *
 * <p>判题内部错误或没有测试点时不给自动分，结果置为“待复核”，由人工决定分数。
 * 历史 {@code ai_evaluation} 表保留但不再写入或读取。
 */
@Service
public class AssessmentEvaluationServiceImpl implements AssessmentEvaluationService {
  private static final Logger log = LoggerFactory.getLogger(AssessmentEvaluationServiceImpl.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AssessmentEvaluationServiceImpl(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 判题完成后重算成绩；不再有 AI 评分环节。 */
  public synchronized void evaluateAfterJudge(long submissionId) {
    refreshResult(submissionId);
  }

  public synchronized void refreshResult(long submissionId) {
    var judge = judge(submissionId);
    if (judge == null) return;

    var review =
        jdbc.queryForList(
            "SELECT score FROM assessment_review WHERE submission_id=? AND status='ACTIVE'"
                + " ORDER BY version DESC LIMIT 1",
            submissionId);
    Integer humanScore =
        review.isEmpty() ? null : ((Number) review.getFirst().get("score")).intValue();
    Integer autoScore = autoScore(judge);
    int itemScore = itemScoreOf(submissionId);

    String scoreSource;
    String resultStatus;
    Integer finalScore;
    if (humanScore != null) {
      scoreSource = "HUMAN";
      resultStatus = "COMPLETE";
      finalScore = scale(itemScore, humanScore);
    } else if (autoScore != null) {
      scoreSource = "AUTO";
      resultStatus = "COMPLETE";
      finalScore = scale(itemScore, autoScore);
    } else {
      scoreSource = "PENDING";
      resultStatus = "NEEDS_REVIEW";
      finalScore = null;
    }

    var now = Timestamp.from(clock.instant());
    int updated =
        jdbc.update(
            "UPDATE assessment_result SET technical_verdict=?,passed_test_cases=?,total_test_cases=?,"
                + "time_ms=?,memory_kb=?,auto_score=?,human_score=?,final_score=?,score_source=?,"
                + "result_status=?,published_at=COALESCE(published_at,?),updated_at=? WHERE submission_id=?",
            judge.verdict().name(),
            judge.passedTestCases(),
            judge.totalTestCases(),
            judge.timeMs(),
            judge.memoryKb(),
            autoScore,
            humanScore,
            finalScore,
            scoreSource,
            resultStatus,
            now,
            now,
            submissionId);
    if (updated == 0) {
      jdbc.update(
          "INSERT INTO assessment_result(submission_id,technical_verdict,passed_test_cases,total_test_cases,"
              + "time_ms,memory_kb,auto_score,human_score,final_score,score_source,result_status,published_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          submissionId,
          judge.verdict().name(),
          judge.passedTestCases(),
          judge.totalTestCases(),
          judge.timeMs(),
          judge.memoryKb(),
          autoScore,
          humanScore,
          finalScore,
          scoreSource,
          resultStatus,
          now,
          now);
    }
    if (autoScore == null)
      log.warn(
          "Submission {} has no automatic score (verdict={}, cases={}/{}), waiting for manual review",
          submissionId,
          judge.verdict(),
          judge.passedTestCases(),
          judge.totalTestCases());
  }

  /**
   * 按通过的测试点计算自动分（0-100）。
   *
   * <p>判题内部错误或没有测试点时不自动给分：这类失败可能是判题环境问题，
   * 直接给 0 分会误伤成员，因此返回 null 交给人工复核。
   */
  public static Integer autoScore(OjJudgeResult judge) {
    if (judge.verdict() == OjVerdict.INTERNAL_ERROR || judge.totalTestCases() <= 0) return null;
    int passed = Math.max(0, Math.min(judge.passedTestCases(), judge.totalTestCases()));
    return (int) Math.round(passed * 100.0 / judge.totalTestCases());
  }

  /**
   * 分值口径换算：final = round(条目分值 × 百分比 / 100)。四舍五入与 autoScore 一致；
   * 条目分值 100（迁移回填值）时结果与旧百分数逐例相等。无百分比（待人工）时为 null。
   */
  public static Integer scale(int itemScore, Integer ratioPercent) {
    if (ratioPercent == null) return null;
    return (int) Math.round(itemScore * ratioPercent / 100.0);
  }

  /** 该提交对应考核条目的分值；条目缺失时按 100 兜底（旧口径防御）。 */
  private int itemScoreOf(long submissionId) {
    var rows =
        jdbc.queryForList(
            "SELECT i.score FROM assessment_item i"
                + " JOIN assessment_submission s ON s.assessment_id=i.assessment_id"
                + " AND s.problem_id=i.problem_id"
                + " WHERE s.id=? AND i.item_type='PROGRAM'",
            submissionId);
    return rows.isEmpty() ? 100 : ((Number) rows.getFirst().get("score")).intValue();
  }

  private OjJudgeResult judge(long submissionId) {
    var rows = jdbc.queryForList("SELECT * FROM oj_judge_result WHERE submission_id=?", submissionId);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    return new OjJudgeResult(
        OjVerdict.valueOf((String) row.get("verdict")),
        ((Number) row.get("passed_test_cases")).intValue(),
        ((Number) row.get("total_test_cases")).intValue(),
        ((Number) row.get("time_ms")).intValue(),
        ((Number) row.get("memory_kb")).intValue(),
        (String) row.get("safe_error"));
  }
}
