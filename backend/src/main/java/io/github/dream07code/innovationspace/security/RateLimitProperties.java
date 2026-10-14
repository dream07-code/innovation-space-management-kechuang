package io.github.dream07code.innovationspace.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth.rate-limit")
public class RateLimitProperties {
  private int requests = 10;
  private int windowSeconds = 60;
  private int capacity = 10_000;

  public int getRequests() {
    return requests;
  }

  public int getWindowSeconds() {
    return windowSeconds;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setRequests(int value) {
    if (value < 1 || value > 1000)
      throw new IllegalArgumentException("Invalid rate-limit requests");
    requests = value;
  }

  public void setWindowSeconds(int value) {
    if (value < 1 || value > 3600) throw new IllegalArgumentException("Invalid rate-limit window");
    windowSeconds = value;
  }

  public void setCapacity(int value) {
    if (value < 1 || value > 100_000)
      throw new IllegalArgumentException("Invalid rate-limit capacity");
    capacity = value;
  }
}
