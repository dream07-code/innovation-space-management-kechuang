package io.github.dream07code.innovationspace.assessment;

import java.util.List;

/**
 * 一次判题请求：语言、代码、测试点，以及题目自身的时间限制。
 *
 * <p>{@code timeLimitMs} 为 0 表示由判题后端决定：远程 Judge0 用请求里带的限制，
 * 本地判题回退到 app.oj.cpu-time-limit-seconds。逐测试点执行的判题后端应该用它
 * 作为单点时限，保证判题口径与题目页展示的一致。
 */
public record OjSubmission(String language, String code, List<TestCase> testCases, int timeLimitMs) {
  public OjSubmission {
    if (language == null || language.isBlank() || code == null || code.isBlank())
      throw new IllegalArgumentException("language and code are required");
    if (code.length() > 200000) throw new IllegalArgumentException("code is too large");
    if (testCases == null || testCases.size() > 100)
      throw new IllegalArgumentException("test cases are required");
    if (timeLimitMs < 0 || timeLimitMs > 60000)
      throw new IllegalArgumentException("invalid time limit");
    testCases = List.copyOf(testCases);
  }

  public OjSubmission(String language, String code, List<TestCase> testCases) {
    this(language, code, testCases, 0);
  }

  public OjSubmission(String language, String code) {
    this(language, code, List.of(), 0);
  }

  public record TestCase(String input, String expectedOutput) {
    public TestCase {
      if (input == null || expectedOutput == null)
        throw new IllegalArgumentException("test input and output are required");
      if (input.length() > 1_000_000 || expectedOutput.length() > 1_000_000)
        throw new IllegalArgumentException("test data is too large");
    }
  }
}
