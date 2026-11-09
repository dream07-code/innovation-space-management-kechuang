package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.util.List;

/**
 * 学习画像的聚合输入之一：某道题在本人的判题记录。
 *
 * <p>只统计已判题（JUDGED / FAILED / NEEDS_REVIEW）的提交：还在判题中的提交既不算学会、
 * 也不算没学会，避免画像在判题完成前后抖动。
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
