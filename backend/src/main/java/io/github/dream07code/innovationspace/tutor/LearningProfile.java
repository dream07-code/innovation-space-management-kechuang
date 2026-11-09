package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.util.List;

/**
 * 学习画像快照。
 *
 * <p>所有数字都来自本人已有的提交、判题与已发布成绩，不含任何模型推断或占位值；
 * 没有做题记录时不会产生快照（见 {@link LearningProfileAggregator}），接口返回空状态。
 *
 * @param passRate 通过题数 ÷ 尝试题数，取整百分比
 * @param averageScore 已发布成绩的平均分；没有任何已发布成绩时为 null
 */
public record LearningProfile(
    long memberId,
    int attemptedProblems,
    int passedProblems,
    int passRate,
    int submissionCount,
    Integer averageScore,
    Instant lastSubmittedAt,
    List<WeakPoint> weakPoints,
    List<Recommendation> recommendations,
    Instant snapshotAt) {

  public LearningProfile {
    weakPoints = weakPoints == null ? List.of() : List.copyOf(weakPoints);
    recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
  }

  /** 薄弱知识点：尝试过、且至少有一道没通过的标签。 */
  public record WeakPoint(String tag, int attempted, int passed, int passRate) {}

  /** 推荐练习：来自公开题库，附带推荐理由里用到的真实数字。 */
  public record Recommendation(
      long problemId, String title, String difficulty, List<String> tags, String reason) {

    public Recommendation {
      tags = tags == null ? List.of() : List.copyOf(tags);
    }
  }
}
