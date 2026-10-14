package io.github.dream07code.innovationspace.assessment;

import java.util.List;

public record OjSubmission(String language, String code, List<TestCase> testCases) {
  public OjSubmission {
    if (language == null || language.isBlank() || code == null || code.isBlank())
      throw new IllegalArgumentException("language and code are required");
    if (code.length() > 200000) throw new IllegalArgumentException("code is too large");
    if (testCases == null || testCases.size() > 100)
      throw new IllegalArgumentException("test cases are required");
    testCases = List.copyOf(testCases);
  }

  public OjSubmission(String language, String code) {
    this(language, code, List.of());
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
