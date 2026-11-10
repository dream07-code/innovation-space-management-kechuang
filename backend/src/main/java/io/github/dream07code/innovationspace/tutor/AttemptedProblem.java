package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.util.List;

/**
 * 学习画像的聚合输入之一：某道题在本人的判题记录。
 *
 * <p>来源包含考核提交与题库练习，两者按「本人 + 题目」合并：只统计已判出结论的记录，
 * 还在判题中的提交既不算学会、也不算没学会，避免画像在判题完成前后抖动。
 * {@code submissions} 是这题的提交数 + 练习次数。
 */
public record AttemptedProblem(
    long problemId,
    String title,
    String difficulty,
    List<String> tags,
    int submissions,
    boolean passed,
    Instant lastSubmittedAt) {

  public AttemptedProblem {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
