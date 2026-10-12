package io.github.dream07code.innovationspace.assessment;
public record OjJudgeResult(
    OjVerdict verdict,
    int passedTestCases,
    int totalTestCases,
    int timeMs,
    int memoryKb,
    String safeError) {
  public OjJudgeResult {
    if (verdict == null) throw new IllegalArgumentException("verdict is required");
    if (passedTestCases < 0 || totalTestCases < 0 || passedTestCases > totalTestCases
        || timeMs < 0 || memoryKb < 0 || (safeError != null && safeError.length() > 500))
      throw new IllegalArgumentException("invalid OJ result");
  }
}
