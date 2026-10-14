package io.github.dream07code.innovationspace.scoring;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai")
public class ScoringProperties {
  private boolean enabled;
  private String provider = "disabled";
  private String baseUrl;
  private String apiKey;
  private String model;
  private int connectTimeoutMs = 3000;
  private int requestTimeoutMs = 30000;
  private int maxRetries = 2;
  private int maxConcurrency = 2;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean value) { enabled = value; }
  public String getProvider() { return provider; }
  public void setProvider(String value) { provider = value; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String value) { baseUrl = value; }
  public String getApiKey() { return apiKey; }
  public void setApiKey(String value) { apiKey = value; }
  public String getModel() { return model; }
  public void setModel(String value) { model = value; }
  public int getConnectTimeoutMs() { return connectTimeoutMs; }
  public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
  public int getRequestTimeoutMs() { return requestTimeoutMs; }
  public void setRequestTimeoutMs(int value) { requestTimeoutMs = value; }
  public int getMaxRetries() { return maxRetries; }
  public void setMaxRetries(int value) { maxRetries = value; }
  public int getMaxConcurrency() { return maxConcurrency; }
  public void setMaxConcurrency(int value) { maxConcurrency = value; }

  public void validate() {
    if (!enabled) return;
    if (!"mock".equals(provider) && !"openai-compatible".equals(provider))
      throw new IllegalArgumentException("Unsupported AI provider");
    if (model != null && !model.isBlank() && model.length() > 128)
      throw new IllegalArgumentException("AI model is invalid");
    if (apiKey != null && !apiKey.isBlank() && apiKey.length() > 4096)
      throw new IllegalArgumentException("AI API key is invalid");
    if ("openai-compatible".equals(provider)) {
      if (model == null || model.isBlank())
        throw new IllegalArgumentException("AI model is required");
      if (apiKey == null || apiKey.isBlank())
        throw new IllegalArgumentException("AI API key is required");
      validateBaseUrl();
    }
    if (connectTimeoutMs < 100 || connectTimeoutMs > 10000
        || requestTimeoutMs < 1000 || requestTimeoutMs > 120000
        || maxRetries < 0 || maxRetries > 3
        || maxConcurrency < 1 || maxConcurrency > 16)
      throw new IllegalArgumentException("Invalid AI timeout/retry configuration");
  }

  private void validateBaseUrl() {
    if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 2048)
      throw new IllegalArgumentException("AI base URL is required");
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("AI base URL is invalid");
    }
    boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
        || "127.0.0.1".equals(uri.getHost())
        || "::1".equals(uri.getHost());
    if (!uri.isAbsolute() || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
        || (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)))
      throw new IllegalArgumentException("AI base URL must be a clean HTTPS origin");
  }

  @Override
  public String toString() {
    return "ScoringProperties[enabled=" + enabled + ",provider=" + provider
        + ",baseUrl=<redacted>,apiKey=<redacted>,model=" + model + "]";
  }
}
