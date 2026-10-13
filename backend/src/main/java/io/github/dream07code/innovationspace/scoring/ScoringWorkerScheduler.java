package io.github.dream07code.innovationspace.scoring;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${app.ai.enabled:false} && ${app.ai.worker-scheduling-enabled:true}")
public class ScoringWorkerScheduler {
  private final ScoringWorker worker;
  private final ThreadPoolExecutor executor;

  public ScoringWorkerScheduler(ScoringWorker worker, ScoringProperties properties) {
    this.worker = worker;
    int concurrency = Math.max(1, properties.getMaxConcurrency());
    this.executor =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(concurrency),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Scheduled(fixedDelayString = "${app.ai.poll-interval-ms:1000}")
  public void poll() {
    for (int i = 0; i < executor.getMaximumPoolSize(); i++) {
      try {
        executor.execute(worker::processOne);
      } catch (RejectedExecutionException full) {
        return;
      }
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }
}
