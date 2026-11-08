package io.github.dream07code.innovationspace.storage;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云 COS 上传实现。
 *
 * <p>不引入官方 SDK：只用到 PUT Object 一个接口，签名逻辑按 COS V5 规范实现即可，
 * 避免为一个上传动作拖进整条 SDK 依赖链。
 */
public class CosReportImageStorage implements ReportImageStorage {
  private static final String ALGORITHM = "HmacSHA1";
  /** 签名有效期；本地时钟偏差留足余量。 */
  private static final long SIGN_TTL_SECONDS = 900;

  private final ReportImageStorageProperties props;
  private final HttpClient http;

  public CosReportImageStorage(ReportImageStorageProperties props) {
    this.props = props;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public boolean available() {
    return !props.getSecretId().isBlank()
        && !props.getSecretKey().isBlank()
        && !props.getBucket().isBlank()
        && !props.getRegion().isBlank()
        && !props.getDomain().isBlank();
  }

  @Override
  public String upload(byte[] content, String contentType, String key) {
    if (!available()) throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    String objectKey = key.startsWith("/") ? key.substring(1) : key;
    String host = props.host();

    long now = Instant.now().getEpochSecond();
    String keyTime = now + ";" + (now + SIGN_TTL_SECONDS);
    String headerString = "content-type=" + urlEncode(contentType) + "&host=" + urlEncode(host);
    String path = "/" + objectKey;
    // 无查询参数：urlParamList 位置留空。
    String httpString = "put\n" + path + "\n\n" + headerString + "\n";
    String signature = signature(keyTime, httpString);

    String authorization =
        "q-sign-algorithm=sha1"
            + "&q-ak="
            + props.getSecretId()
            + "&q-sign-time="
            + keyTime
            + "&q-key-time="
            + keyTime
            + "&q-header-list=content-type;host"
            + "&q-url-param-list="
            + "&q-signature="
            + signature;

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://" + host + path))
            .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
            .header("Content-Type", contentType)
            .header("Authorization", authorization)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    } catch (Exception e) {
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    }
    if (response.statusCode() / 100 != 2) {
      // 只报状态码，不回显响应体（可能含桶名、请求 id 等内部信息）。
      throw new ApiException(502, ErrorCode.SERVICE_UNAVAILABLE);
    }
    return props.getDomain() + "/" + objectKey;
  }

  /** COS V5 签名：signKey = HMAC-SHA1(keyTime, SecretKey)，再对 stringToSign 做一次 HMAC。 */
  private String signature(String keyTime, String httpString) {
    byte[] signKey = hmacSha1(keyTime.getBytes(StandardCharsets.UTF_8));
    String stringToSign =
        "sha1\n" + keyTime + "\n" + sha1Hex(httpString.getBytes(StandardCharsets.UTF_8)) + "\n";
    return hex(hmac(signKey, stringToSign.getBytes(StandardCharsets.UTF_8)));
  }

  private byte[] hmacSha1(byte[] data) {
    return hmac(props.getSecretKey().getBytes(StandardCharsets.UTF_8), data);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    }
  }

  private static String sha1Hex(byte[] data) {
    try {
      return hex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
    } catch (GeneralSecurityException e) {
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  /** COS 要求的 RFC 3986 编码：保留 A-Z a-z 0-9 - _ . ~，其余（含空格）转大写百分号编码。 */
  static String urlEncode(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_'
          || c == '.'
          || c == '~') {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }
}
