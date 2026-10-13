package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.adapter.MockOjAdapter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OjAdapterTest {
  private static final java.util.List<OjSubmission.TestCase> TWO_CASES =
      java.util.List.of(
          new OjSubmission.TestCase("1", "1"),
          new OjSubmission.TestCase("2", "2"));

  @Test
  void mockOjMapsSafeVerdictsForEveryFailureMode() {
    var adapter = new MockOjAdapter();
    assertEquals(OjVerdict.ACCEPTED, adapter.judge(new OjSubmission("java", "class Main {}", TWO_CASES)).verdict());
    assertEquals(OjVerdict.COMPILATION_ERROR, adapter.judge(new OjSubmission("java", "compile-error", TWO_CASES)).verdict());
    assertEquals(OjVerdict.WRONG_ANSWER, adapter.judge(new OjSubmission("java", "wrong-answer", TWO_CASES)).verdict());
    assertEquals(OjVerdict.TIME_LIMIT_EXCEEDED, adapter.judge(new OjSubmission("java", "timeout", TWO_CASES)).verdict());
    assertEquals(OjVerdict.RUNTIME_ERROR, adapter.judge(new OjSubmission("java", "runtime-error", TWO_CASES)).verdict());
    for (var result : new OjJudgeResult[] {
        adapter.judge(new OjSubmission("java", "compile-error", TWO_CASES)),
        adapter.judge(new OjSubmission("java", "wrong-answer", TWO_CASES))}) {
      assertNotNull(result.safeError());
      assertEquals(2, result.testCaseResults().size());
      assertEquals(1, result.testCaseResults().getFirst().position());
      assertEquals(2, result.testCaseResults().getLast().position());
      assertFalse(String.valueOf(result).contains("class Main"));
      assertFalse(String.valueOf(result).contains("\"input\""));
      assertFalse(String.valueOf(result).contains("\"expectedOutput\""));
    }
  }
}
