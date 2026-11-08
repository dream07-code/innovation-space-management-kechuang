package io.github.dream07code.innovationspace.scoring;

import java.util.List;

/**
 * 编程作业的 AI 批改。实现复用日报/周报评分的同一套 openai-compatible 配置，不新增模型。
 *
 * <p>实现必须保证：不可用时抛 {@link Unavailable}，绝不返回占位文案或示例结论——
 * 报告页宁可显示"AI 批改暂不可用"，也不能给出看起来像真的假批改。
 */
public interface SubmissionReportAi {

  Analysis analyze(ReportInput input);

  /** 判题客观输入：题面、代码、判定与失败用例。 */
  record ReportInput(
      String title,
      String difficulty,
      String description,
      String language,
      String code,
      String verdict,
      int passed,
      int total,
      String safeError,
      List<FailedCase> failedCases) {

    public ReportInput {
      failedCases = failedCases == null ? List.of() : List.copyOf(failedCases);
    }
  }

  /** 失败用例的截断快照；字段可能为空（例如编译错误没有实际输出）。 */
  record FailedCase(
      int position, String verdict, String input, String actualOutput, String expectedOutput) {}

  /** 模型给出的批改结论：错因解析 + 改进建议。 */
  record Analysis(String cause, List<String> advice, String model) {

    public Analysis {
      if (cause == null || cause.isBlank()) throw new IllegalArgumentException("cause is required");
      if (advice == null || advice.isEmpty()) advice = List.of();
      advice = List.copyOf(advice);
    }
  }

  /** 调用失败或未配置。报告接口据此回 503，不降级成假数据。 */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }

    public Unavailable(String message) {
      super(message);
    }
  }
}
