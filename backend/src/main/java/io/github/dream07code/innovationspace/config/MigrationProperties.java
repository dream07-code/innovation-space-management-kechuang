package io.github.dream07code.innovationspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.migration")
public class MigrationProperties {
  private boolean enabled;
  private String mode = "disabled";
    private String requiredVersion = "5";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    if (!"disabled".equals(mode) && !"migrate".equals(mode))
      throw new IllegalArgumentException("Unsupported maintenance mode");
    this.mode = mode;
  }

  public String getRequiredVersion() {
    return requiredVersion;
  }

  public void setRequiredVersion(String requiredVersion) {
    this.requiredVersion = requiredVersion;
  }
}
