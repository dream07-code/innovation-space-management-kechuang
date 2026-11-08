package io.github.dream07code.innovationspace.scoring;

import java.util.List;

/**
 * 就一次判题结果与学生多轮对话的 AI 助教。
 *
 * <p>与批改报告共用同一套 openai-compatible 配置与模型（{@code app.ai.*}），不新增模型接入；
 * 差别只在形态：批改是单轮出结构化结论，助教是学生追问、按轮次回答。
 *
 * <p>对话历史由调用方逐轮传入，服务端不保存会话，因此不需要新增数据库结构；
 * 代价是每轮都要重发上下文，由 {@link SubmissionTutorRubric} 统一截断控制成本。
 *
 * <p>实现必须保证：不可用时抛 {@link Unavailable}，绝不返回占位回答——
 * 宁可明确告知"助教暂不可用"，也不能给出看似真实的教学内容。
 */
public interface SubmissionTutorAi {

  Reply chat(TutorInput input);

  /** 一次提问所需的全部材料：题目、代码、判题结果、历史对话与本次问题。 */
  record TutorInput(
      Problem problem,
      String language,
      String code,
      Judge judge,
      List<Turn> history,
      String message) {

    public TutorInput {
      history = history == null ? List.of() : List.copyOf(history);
    }
  }

  /** 题面。字段允许为空（题目可能只填了简介），由提示词侧做兜底。 */
  record Problem(
      String title,
      String difficulty,
      String description,
      String inputFormat,
      String outputFormat,
      String tags) {}

  /** 客观判题结果，全部来自 Judge0 落库的数据，不做本地推断。 */
  record Judge(
      String verdict, int passed, int total, String safeError, List<FailedCase> failedCases) {

    public Judge {
      failedCases = failedCases == null ? List.of() : List.copyOf(failedCases);
    }
  }

  record FailedCase(
      int position, String verdict, String input, String actualOutput, String expectedOutput) {}

  /** 一轮历史对话，只允许 user / assistant 两种角色。 */
  record Turn(String role, String content) {

    public Turn {
      if (!"user".equals(role) && !"assistant".equals(role))
        throw new IllegalArgumentException("role must be user or assistant");
      content = content == null ? "" : content;
    }
  }

  record Reply(String content, String model) {

    public Reply {
      if (content == null || content.isBlank())
        throw new IllegalArgumentException("content is required");
    }
  }

  /** 调用失败或未配置。接口据此返回可展示的降级说明，不编造教学内容。 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }

    public Unavailable(String message) {
      super(message);
    }
  }
}
