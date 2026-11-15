package io.github.dream07code.innovationspace.problemgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiProblemCancellationRegistryTest {

  @Test
  void cancelIsScopedToMemberAndRequest() {
    var registry = new AiProblemCancellationRegistry();

    assertFalse(registry.isCancelled(10, "req-1"));
    registry.cancel(10, "req-1");

    assertTrue(registry.isCancelled(10, "req-1"));
    // 别的成员拿到同一个 requestId 也取消不了别人的请求。
    assertFalse(registry.isCancelled(11, "req-1"));
    assertFalse(registry.isCancelled(10, "req-2"));
  }

  @Test
  void clearForgetsTheRequest() {
    var registry = new AiProblemCancellationRegistry();

    registry.cancel(10, "req-1");
    registry.clear(10, "req-1");

    assertFalse(registry.isCancelled(10, "req-1"));
  }
}
