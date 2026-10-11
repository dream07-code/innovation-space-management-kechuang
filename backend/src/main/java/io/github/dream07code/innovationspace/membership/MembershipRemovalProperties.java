package io.github.dream07code.innovationspace.membership;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.membership")
public class MembershipRemovalProperties implements InitializingBean {
  private boolean autoRemovalEnabled = true;
  private long removalCheckIntervalMs = 3600000L;
  private int removalBatchSize = 500;

  public boolean isAutoRemovalEnabled() { return autoRemovalEnabled; }
  public void setAutoRemovalEnabled(boolean value) { autoRemovalEnabled = value; }
  public long getRemovalCheckIntervalMs() { return removalCheckIntervalMs; }
  public void setRemovalCheckIntervalMs(long value) { removalCheckIntervalMs = value; }
  public int getRemovalBatchSize() { return removalBatchSize; }
  public void setRemovalBatchSize(int value) { removalBatchSize = value; }

  @Override
  public void afterPropertiesSet() {
    if (removalCheckIntervalMs < 60000 || removalCheckIntervalMs > 86400000)
      throw new IllegalArgumentException("Invalid membership removal check interval");
    if (removalBatchSize < 1 || removalBatchSize > 5000)
      throw new IllegalArgumentException("Invalid membership removal batch size");
  }
}
