package io.github.dream07code.innovationspace.assessment.adapter.judge0;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oj")
public class Judge0OjProperties {
  private String provider = "mock";
  private String baseUrl;
  private String apiToken;
  private int connectTimeoutMs = 3000;
  private int requestTimeoutMs = 30000;
  private int cpuTimeLimitSeconds = 2;
  private int memoryLimitKb = 128000;
  private int maxResponseBytes = 1048576;
  private boolean useRlimitFallback = false;

  public String getProvider() { return provider; }
  public void setProvider(String value) { provider = value; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String value) { baseUrl = value; }
  public String getApiToken() { return apiToken; }
  public void setApiToken(String value) { apiToken = value; }
  public int getConnectTimeoutMs() { return connectTimeoutMs; }
  public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
  public int getRequestTimeoutMs() { return requestTimeoutMs; }
  public void setRequestTimeoutMs(int value) { requestTimeoutMs = value; }
  public int getCpuTimeLimitSeconds() { return cpuTimeLimitSeconds; }
  public void setCpuTimeLimitSeconds(int value) { cpuTimeLimitSeconds = value; }
  public int getMemoryLimitKb() { return memoryLimitKb; }
  public void setMemoryLimitKb(int value) { memoryLimitKb = value; }
  public int getMaxResponseBytes() { return maxResponseBytes; }
  public boolean isUseRlimitFallback() { return useRlimitFallback; }
  public void setUseRlimitFallback(boolean value) { useRlimitFallback = value; }
  public void setMaxResponseBytes(int value) { maxResponseBytes = value; }

  public void validate() {
    if (!"mock".equals(provider) && !"judge0".equals(provider))
      throw new IllegalArgumentException("Unsupported OJ provider");
    if (!"judge0".equals(provider)) return;
    if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 2048)
      throw new IllegalArgumentException("Judge0 base URL is required");
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Judge0 base URL is invalid");
    }
    boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
        || "127.0.0.1".equals(uri.getHost())
        || "::1".equals(uri.getHost());
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
        || (!"https".equalsIgnoreCase(uri.getScheme())
            && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)))
      throw new IllegalArgumentException("Judge0 base URL must be a clean HTTPS origin");
    if (apiToken != null && apiToken.length() > 4096)
      throw new IllegalArgumentException("Judge0 API token is too large");
    if (connectTimeoutMs < 100 || connectTimeoutMs > 10000
        || requestTimeoutMs < 1000 || requestTimeoutMs > 120000
        || cpuTimeLimitSeconds < 1 || cpuTimeLimitSeconds > 30
        || memoryLimitKb < 1024 || memoryLimitKb > 1048576
        || maxResponseBytes < 1024 || maxResponseBytes > 4194304)
      throw new IllegalArgumentException("Invalid Judge0 limits");
  }

  @Override
  public String toString() {
    return "Judge0OjProperties[provider=" + provider + ",baseUrl=<redacted>,apiToken=<redacted>,useRlimitFallback=" + useRlimitFallback + "]";
  }
}
