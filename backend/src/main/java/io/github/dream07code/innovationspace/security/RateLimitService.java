package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.time.Clock;
import java.util.HashMap;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
  private record Bucket(long window, int count) {}

  private final HashMap<String, Bucket> buckets = new HashMap<>();
  private final Clock clock;
  private final RateLimitProperties properties;

  public RateLimitService(Clock clock) {
    this(clock, new RateLimitProperties());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public RateLimitService(Clock clock, RateLimitProperties properties) {
    this.clock = clock;
    this.properties = properties;
  }

  public synchronized void check(String key) {
    check(key, properties.getRequests(), properties.getWindowSeconds());
  }

  /**
   * 使用独立阈值计数。
   *
   * <p>图片上传这类"一次操作可能连发多个请求"的场景需要比登录更宽的配额，
   * 但又不能直接放宽全局登录限流，因此单独指定窗口与次数。
   */
  public synchronized void check(String key, int requests, int windowSeconds) {
    long window = clock.instant().getEpochSecond() / windowSeconds;
    buckets.entrySet().removeIf(e -> e.getValue().window() != window);
    var previous = buckets.get(key);
    int count = previous == null ? 0 : previous.count();
    if (count >= requests || (previous == null && buckets.size() >= properties.getCapacity()))
      throw new ApiException(429, ErrorCode.RATE_LIMITED);
    buckets.put(key, new Bucket(window, count + 1));
  }

  /** 按 key 自带的窗口计算剩余秒数；无自定义窗口时退回全局配置。 */
  public int retryAfter(int windowSeconds) {
    return windowSeconds - (int) (clock.instant().getEpochSecond() % windowSeconds);
  }

  public int retryAfter() {
    return retryAfter(properties.getWindowSeconds());
  }
}
