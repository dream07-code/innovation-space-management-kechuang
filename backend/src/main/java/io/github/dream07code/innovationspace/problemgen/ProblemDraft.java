package io.github.dream07code.innovationspace.problemgen;

import java.util.List;
import java.util.Map;

/**
 * AI 生成的一道题：题面 + 函数签名 + 参考解法 + 测试点。
 *
 * <p>已经过 {@link ProblemDraftParser} 的结构校验，字段可以直接落库；
 * 是否「真的能做对」由调用方用参考解法跑测试点确认，本对象不承担判题。
 */
public record ProblemDraft(
    String title,
    String summary,
    String description,
    String inputFormat,
    String outputFormat,
    List<Map<String, Object>> examples,
    List<String> tags,
    String functionName,
    List<String> parameterTypes,
    String returnType,
    int timeLimitMs,
    int memoryLimitKb,
    String referenceCode,
    List<TestCase> testCases) {

  public ProblemDraft {
    examples = List.copyOf(examples);
    parameterTypes = List.copyOf(parameterTypes);
    tags = List.copyOf(tags);
    testCases = List.copyOf(testCases);
  }

  /**
   * 一个测试点。
   *
   * <p>{@code boundary} 标记边界用例：题目要求至少 1 个，用来覆盖空数组、单元素、
   * 极值这类容易写错的分支。判题本身不区分类型，标识只用于生成时的数量校验。
   */
  public record TestCase(String input, String expectedOutput, boolean boundary) {}
}
