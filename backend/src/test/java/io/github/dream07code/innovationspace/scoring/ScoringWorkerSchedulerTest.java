package io.github.dream07code.innovationspace.scoring;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScoringWorkerSchedulerTest {
  @Test
  void pollExecutesWorkerWithConfiguredConcurrency() throws Exception {
    var worker = mock(ScoringWorker.class);
    var properties = new ScoringProperties();
    properties.setMaxConcurrency(1);
    var started = new CountDownLatch(1);
    when(worker.processOne())
        .thenAnswer(
            invocation -> {
              started.countDown();
              return true;
            });

    var scheduler = new ScoringWorkerScheduler(worker, properties);
    try {
      scheduler.poll();
      assertTrue(started.await(1, TimeUnit.SECONDS), "scheduled worker should execute");
    } finally {
      scheduler.shutdown();
    }
  }
}
