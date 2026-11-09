package io.github.dream07code.innovationspace.scoring;

import java.util.List;

/**
 * 通用 AI 助教对话（不绑定某次提交）。
 *
 * <p>与批改报告、提交助教共用同一套 openai-compatible 配置与模型（{@code app.ai.*}），
 * 差别只在上下文：这里不读学生代码与判题结果，只带「当前选中的题目」与问题本身，
 * 因此学生不做题也能问知识点。
 *
 * <p>会话历史由服务端按 conversationId 从 tutor_message 取最近若干条后传进来；
 * 本接口仍然只负责一次模型调用，不读也不写数据库。
 * 不可用时抛 {@link Unavailable}，绝不返回占位回答。
 */
public interface TutorChatAi {

  Reply chat(ChatInput input);

  /** 一次提问所需的材料：选中的题目（可为空）、历史对话与本次问题。 */
  record ChatInput(Problem problem, List<Turn> history, String message) {

    public ChatInput {
      history = history == null ? List.of() : List.copyOf(history);
    }
  }

  /** 题目上下文；字段允许为空（题目可能只填了描述），由提示词侧兜底。 */
  record Problem(
      String title,
      String difficulty,
      String description,
      String inputFormat,
      String outputFormat,
      String tags,
      String functionName,
      String parameterTypes,
      String returnType) {}

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
