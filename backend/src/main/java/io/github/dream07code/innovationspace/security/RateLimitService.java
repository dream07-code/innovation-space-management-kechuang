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
    long window = clock.instant().getEpochSecond() / properties.getWindowSeconds();
    buckets.entrySet().removeIf(e -> e.getValue().window() != window);
    var previous = buckets.get(key);
    int count = previous == null ? 0 : previous.count();
    if (count >= properties.getRequests()
        || (previous == null && buckets.size() >= properties.getCapacity()))
      throw new ApiException(429, ErrorCode.RATE_LIMITED);
    buckets.put(key, new Bucket(window, count + 1));
  }

  public int retryAfter() {
    return properties.getWindowSeconds()
        - (int) (clock.instant().getEpochSecond() % properties.getWindowSeconds());
  }
}
