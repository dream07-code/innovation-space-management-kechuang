package io.github.dream07code.innovationspace.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 腾讯云 COS 配置。密钥只从环境变量注入，不写进仓库与配置文件。 */
@ConfigurationProperties("app.storage.cos")
public class ReportImageStorageProperties {
  private String secretId = "";
  private String secretKey = "";
  private String bucket = "";
  private String region = "ap-beijing";
  /** 桶的访问域名，用于拼接最终 URL；不带结尾斜杠。 */
  private String domain = "";
  /** 对象键前缀，便于按业务归档与配置最小权限策略。 */
  private String keyPrefix = "reports";
  private int connectTimeoutMs = 5000;
  private int requestTimeoutMs = 30000;

  public String getSecretId() {
    return secretId;
  }

  public void setSecretId(String secretId) {
    this.secretId = secretId == null ? "" : secretId.trim();
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey == null ? "" : secretKey.trim();
  }

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket == null ? "" : bucket.trim();
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region == null ? "" : region.trim();
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    String value = domain == null ? "" : domain.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    this.domain = value;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    String value = keyPrefix == null ? "" : keyPrefix.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    this.keyPrefix = value;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getRequestTimeoutMs() {
    return requestTimeoutMs;
  }

  public void setRequestTimeoutMs(int requestTimeoutMs) {
    this.requestTimeoutMs = requestTimeoutMs;
  }

  /** 对象存储的 HTTP 端点主机名，签名与请求都基于它。 */
  public String host() {
    return bucket + ".cos." + region + ".myqcloud.com";
  }
}
