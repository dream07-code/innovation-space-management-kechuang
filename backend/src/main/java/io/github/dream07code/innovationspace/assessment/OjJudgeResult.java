package io.github.dream07code.innovationspace.assessment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record OjJudgeResult(
    OjVerdict verdict,
    int passedTestCases,
    int totalTestCases,
    int timeMs,
    int memoryKb,
    String safeError,
    List<TestCaseResult> testCaseResults) {
  public OjJudgeResult {
    if (verdict == null) throw new IllegalArgumentException("verdict is required");
    if (passedTestCases < 0 || totalTestCases < 0 || passedTestCases > totalTestCases
        || timeMs < 0 || memoryKb < 0 || (safeError != null && safeError.length() > 500))
      throw new IllegalArgumentException("invalid OJ result");
    if (testCaseResults == null || testCaseResults.size() > 100)
      throw new IllegalArgumentException("invalid test case results");
    testCaseResults = List.copyOf(testCaseResults);
    if (!testCaseResults.isEmpty() && testCaseResults.size() != totalTestCases)
      throw new IllegalArgumentException("test case result count mismatch");
    var positions = new HashSet<Integer>();
    for (int index = 0; index < testCaseResults.size(); index++) {
      var result = testCaseResults.get(index);
      if (result == null || result.position() != index + 1 || !positions.add(result.position()))
        throw new IllegalArgumentException("test case positions must be ordered and unique");
    }
  }

  public OjJudgeResult(
      OjVerdict verdict,
      int passedTestCases,
      int totalTestCases,
      int timeMs,
      int memoryKb,
      String safeError) {
    this(verdict, passedTestCases, totalTestCases, timeMs, memoryKb, safeError, List.of());
  }

  public record TestCaseResult(
      int position,
      OjVerdict verdict,
      int timeMs,
      int memoryKb,
      String safeError,
      String actualOutput) {
    private static final int MAX_ACTUAL_OUTPUT = 2000;

    public TestCaseResult {
      if (position < 1 || position > 100 || verdict == null || timeMs < 0 || memoryKb < 0
          || (safeError != null && safeError.length() > 500))
        throw new IllegalArgumentException("invalid OJ test case result");
      if (actualOutput != null && actualOutput.length() > MAX_ACTUAL_OUTPUT)
        actualOutput = actualOutput.substring(0, MAX_ACTUAL_OUTPUT);
    }

    public TestCaseResult(
        int position, OjVerdict verdict, int timeMs, int memoryKb, String safeError) {
      this(position, verdict, timeMs, memoryKb, safeError, null);
    }
  }
}
