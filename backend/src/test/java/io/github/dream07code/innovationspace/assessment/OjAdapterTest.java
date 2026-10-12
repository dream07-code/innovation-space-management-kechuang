package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.adapter.MockOjAdapter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OjAdapterTest {
  @Test
  void mockOjMapsSafeVerdictsForEveryFailureMode() {
    var adapter = new MockOjAdapter();
    assertEquals(OjVerdict.ACCEPTED, adapter.judge(new OjSubmission("java", "class Main {}")).verdict());
    assertEquals(OjVerdict.COMPILATION_ERROR, adapter.judge(new OjSubmission("java", "compile-error")).verdict());
    assertEquals(OjVerdict.WRONG_ANSWER, adapter.judge(new OjSubmission("java", "wrong-answer")).verdict());
    assertEquals(OjVerdict.TIME_LIMIT_EXCEEDED, adapter.judge(new OjSubmission("java", "timeout")).verdict());
    assertEquals(OjVerdict.RUNTIME_ERROR, adapter.judge(new OjSubmission("java", "runtime-error")).verdict());
    for (var result : new OjJudgeResult[] {
        adapter.judge(new OjSubmission("java", "compile-error")),
        adapter.judge(new OjSubmission("java", "wrong-answer"))}) {
      assertNotNull(result.safeError());
      assertFalse(String.valueOf(result).contains("class Main"));
    }
  }
}
