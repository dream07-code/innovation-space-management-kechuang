package io.github.dream07code.innovationspace.problemgen;

/**
 * 出题模型。
 *
 * <p>与批改、助教共用同一套 {@code app.ai.*} 配置，只换提示词。实现必须保证：
 * 不可用时抛 {@link Unavailable}，不返回占位题目——生成失败宁可让前端提示重试，
 * 也不能把编造的题面写进题库。
 */
public interface ProblemGenerator {

  ProblemDraft generate(Request request);

  /**
   * 一次出题请求。
   *
   * @param topic 知识点，例如「动态规划」
   * @param difficulty EASY / MEDIUM / HARD
   * @param category 题型，例如「数组」「字符串」；可为空
   * @param feedback 上一次自测失败的原因；首次生成时为 null，重试时带上让模型纠正
   */
  record Request(String topic, String difficulty, String category, String feedback) {

    public Request {
      if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic is required");
      if (difficulty == null || difficulty.isBlank())
        throw new IllegalArgumentException("difficulty is required");
    }
  }

  /** 未配置模型或调用失败。 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }

    public Unavailable(String message) {
      super(message);
    }
  }
}
