package io.github.dream07code.innovationspace.tutor;

import java.util.List;

/**
 * 题库里的候选题，只用于推荐练习：只下发公开题目（PUBLISHED），并且只有 id/标题/难度/标签，
 * 不带题面与测试数据。
 */
public record CandidateProblem(long id, String title, String difficulty, List<String> tags) {

  public CandidateProblem {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
