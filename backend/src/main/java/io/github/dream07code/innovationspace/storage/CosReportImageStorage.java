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
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 腾讯云 COS 上传实现。
 *
 * <p>不引入官方 SDK：只用到 PUT Object 一个接口，签名逻辑按 COS V5 规范实现即可，
 * 避免为一个上传动作拖进整条 SDK 依赖链。
 */
public class CosReportImageStorage implements ReportImageStorage {
  private static final Logger log = LoggerFactory.getLogger(CosReportImageStorage.class);
  private static final String ALGORITHM = "HmacSHA1";
  /** 签名有效期；本地时钟偏差留足余量。 */
  private static final long SIGN_TTL_SECONDS = 900;
  /** 连接失败最多尝试次数与退避间隔。 */
  private static final int MAX_ATTEMPTS = 2;
  private static final long RETRY_BACKOFF_MS = 400;
  /** COS 返回的错误码在响应体里，只截取这一小段用于排障。 */
  private static final Pattern COS_ERROR_CODE = Pattern.compile("<Code>([^<]{0,60})</Code>");

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
    String path = "/" + objectKey;

    long now = Instant.now().getEpochSecond();
    String authorization = authorization(path, contentType, now);

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://" + host + path))
            .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
            .header("Content-Type", contentType)
            .header("Authorization", authorization)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .build();

    HttpResponse<String> response = send(request, objectKey);
    if (response.statusCode() / 100 != 2) {
      // 响应体可能含桶名、请求 id 等内部信息，只留错误码进日志。
      log.warn(
          "COS 拒绝上传 key={} status={} code={}",
          objectKey,
          response.statusCode(),
          errorCode(response.body()));
      throw new ApiException(502, ErrorCode.SERVICE_UNAVAILABLE);
    }
    return props.getDomain() + "/" + objectKey;
  }

  /** 连接层失败重试一次：公网上偶发的 connect timeout 不该让学生的上传直接失败。 */
  private HttpResponse<String> send(HttpRequest request, String objectKey) {
    for (int attempt = 1; ; attempt++) {
      try {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("COS 上传被中断 key={}", objectKey);
        throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
      } catch (Exception e) {
        if (attempt >= MAX_ATTEMPTS) {
          log.warn("COS 上传失败 key={} attempts={}: {}", objectKey, attempt, e.toString());
          throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
        }
        log.info("COS 上传重试 key={} attempt={}: {}", objectKey, attempt, e.toString());
        try {
          Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
        }
      }
    }
  }

  /** 组装 PUT Object 的 Authorization 头；epochSeconds 抽出来便于用固定时间做断言。 */
  String authorization(String path, String contentType, long epochSeconds) {
    String keyTime = epochSeconds + ";" + (epochSeconds + SIGN_TTL_SECONDS);
    String headerString = "content-type=" + urlEncode(contentType) + "&host=" + urlEncode(props.host());
    // 无查询参数：urlParamList 位置留空。
    String httpString = "put\n" + path + "\n\n" + headerString + "\n";
    String signature = signature(keyTime, httpString);
    return "q-sign-algorithm=sha1"
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
  }

  /** 桶的访问域名（不带结尾斜杠）；迁移时用它识别库里的 COS 地址前缀。 */
  public String domain() {
    return props.getDomain();
  }

  /**
   * 下载一个已上传的对象，供「历史图片迁到本地」使用。
   *
   * <p>GET 请求只签 host 头，与 PUT 的签名串不同（没有 content-type），所以单独拼一次；
   * 返回的字节由调用方决定怎么处理（压缩、落盘）。
   */
  public byte[] download(String url) {
    if (!available()) throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    String prefix = props.getDomain() + "/";
    if (url == null || !url.startsWith(prefix))
      throw new IllegalArgumentException("not a COS url of this bucket: " + url);
    String objectKey = url.substring(prefix.length());
    String path = "/" + objectKey;

    long now = Instant.now().getEpochSecond();
    String keyTime = now + ";" + (now + SIGN_TTL_SECONDS);
    String headerString = "host=" + urlEncode(props.host());
    String signature = signature(keyTime, "get\n" + path + "\n\n" + headerString + "\n");
    String authorization =
        "q-sign-algorithm=sha1"
            + "&q-ak="
            + props.getSecretId()
            + "&q-sign-time="
            + keyTime
            + "&q-key-time="
            + keyTime
            + "&q-header-list=host"
            + "&q-url-param-list="
            + "&q-signature="
            + signature;

    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://" + props.host() + path))
            .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
            .header("Authorization", authorization)
            .GET()
            .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    } catch (Exception e) {
      log.warn("COS 下载失败 key={}: {}", objectKey, e.toString());
      throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);
    }
    if (response.statusCode() / 100 != 2) {
      log.warn("COS 拒绝下载 key={} status={}", objectKey, response.statusCode());
      throw new ApiException(502, ErrorCode.SERVICE_UNAVAILABLE);
    }
    return response.body();
  }

  /**
   * COS V5 签名：signKey = hex(HMAC-SHA1(KeyTime, SecretKey))，再对 stringToSign 做一次 HMAC。
   *
   * <p>注意两级之间用的是 signKey 的 **十六进制文本** 而不是原始字节，写成原始字节会稳定返回
   * SignatureDoesNotMatch。
   */
  private String signature(String keyTime, String httpString) {
    byte[] signKey =
        hex(hmac(props.getSecretKey().getBytes(StandardCharsets.UTF_8), keyTime.getBytes(StandardCharsets.UTF_8)))
            .getBytes(StandardCharsets.UTF_8);
    String stringToSign =
        "sha1\n" + keyTime + "\n" + sha1Hex(httpString.getBytes(StandardCharsets.UTF_8)) + "\n";
    return hex(hmac(signKey, stringToSign.getBytes(StandardCharsets.UTF_8)));
  }

  private static String errorCode(String body) {
    if (body == null) return "-";
    var matcher = COS_ERROR_CODE.matcher(body);
    return matcher.find() ? matcher.group(1) : "-";
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
