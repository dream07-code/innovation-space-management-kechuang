package io.github.dream07code.innovationspace.problemgen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * AI 出题的取消标记。
 *
 * <p>出题是同步长请求（模型生成 + 参考解法逐点自测，约一分钟），学生点「取消」时
 * 正在跑的模型调用无法中断，但可以在「写库前」看到标记并放弃落库——取消的语义是
 * 「这次不产生题目」，不是「立刻掐断 HTTP 连接」。
 *
 * <p>标记按「成员 + 请求 id」隔离，避免拿到别人的 requestId 互相取消；
 * 记录带 TTL，迟到或丢失的取消请求不会永久占内存。
 */
@Component
public class AiProblemCancellationRegistry {
  private static final long TTL_MS = 10 * 60 * 1000L;

  private final Map<String, Long> cancelled = new ConcurrentHashMap<>();

  public void cancel(long memberId, String requestId) {
    prune();
    cancelled.put(key(memberId, requestId), System.currentTimeMillis());
  }

  public boolean isCancelled(long memberId, String requestId) {
    var markedAt = cancelled.get(key(memberId, requestId));
    return markedAt != null && System.currentTimeMillis() - markedAt < TTL_MS;
  }

  /** 请求结束时清掉标记，避免同一 id 被重复使用。 */
  public void clear(long memberId, String requestId) {
    cancelled.remove(key(memberId, requestId));
  }

  private void prune() {
    long now = System.currentTimeMillis();
    cancelled.entrySet().removeIf(entry -> now - entry.getValue() >= TTL_MS);
  }

  private static String key(long memberId, String requestId) {
    return memberId + ":" + requestId;
  }
}
