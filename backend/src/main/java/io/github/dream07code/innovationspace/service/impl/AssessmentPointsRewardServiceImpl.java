package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.service.AssessmentPointsRewardService;
import io.github.dream07code.innovationspace.service.PointLedgerService;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 积分奖励结算：delta = round(20 × 本人总分 ÷ 全场最高分)。
 *
 * 口径与结果页 totalScore 一致（每人最新一次提交的 final_score 之和 + 答题卡
 * total_score）；并列第一同分自然并列，最高分为 0 整场不发。发放对象与排行
 * 榜资格一致：考核方向的 ACTIVE 学生。eventKey 绑定考核与成员保证幂等。
 */
@Service
public class AssessmentPointsRewardServiceImpl implements AssessmentPointsRewardService {
  private static final int TOP_REWARD = 20;

  private final JdbcTemplate jdbc;
  private final PointLedgerService points;
  private final Clock clock;

  public AssessmentPointsRewardServiceImpl(
      JdbcTemplate jdbc, PointLedgerService points, Clock clock) {
    this.jdbc = jdbc;
    this.points = points;
    this.clock = clock;
  }

  @Override
  public void settle(long assessmentId) {
    Boolean enabled =
        jdbc.queryForObject(
            "SELECT points_enabled FROM assessment WHERE id=?", Boolean.class, assessmentId);
    if (enabled == null || !enabled) return;
    Long directionId =
        jdbc.queryForObject(
            "SELECT direction_id FROM assessment WHERE id=?", Long.class, assessmentId);
    if (directionId == null) return;

    var rows =
        jdbc.queryForList(
            "WITH latest AS ("
                + " SELECT s.member_id, s.id AS submission_id,"
                + " ROW_NUMBER() OVER (PARTITION BY s.member_id ORDER BY s.submitted_at DESC, s.id DESC) rn"
                + " FROM assessment_submission s WHERE s.assessment_id=?)"
                + " SELECT m.id AS member_id,"
                + " COALESCE(SUM(CASE WHEN l.rn=1 THEN r.final_score END),0)"
                + "   + COALESCE(MAX(sh.total_score),0) AS total"
                + " FROM member m"
                + " JOIN app_user u ON u.member_id=m.id"
                + " LEFT JOIN latest l ON l.member_id=m.id AND l.rn=1"
                + " LEFT JOIN assessment_result r ON r.submission_id=l.submission_id"
                + " LEFT JOIN assessment_answer_sheet sh ON sh.assessment_id=? AND sh.member_id=m.id"
                + " WHERE m.direction_id=? AND m.membership_status='ACTIVE' AND u.role='STUDENT'"
                + " GROUP BY m.id",
            assessmentId,
            assessmentId,
            directionId);
    if (rows.isEmpty()) return;

    long topScore = 0;
    for (var row : rows) {
      topScore = Math.max(topScore, ((Number) row.get("total")).longValue());
    }
    if (topScore <= 0) return;

    var effective = Timestamp.from(clock.instant());
    for (var row : rows) {
      long memberId = ((Number) row.get("member_id")).longValue();
      long total = ((Number) row.get("total")).longValue();
      int delta = (int) Math.round(TOP_REWARD * (double) total / topScore);
      if (delta <= 0) continue;
      points.appendIfAbsent(
          memberId,
          delta,
          "ASSESSMENT_REWARD",
          "ASSESSMENT_REWARD:" + assessmentId + ":" + memberId,
          clock.instant());
    }
  }
}
