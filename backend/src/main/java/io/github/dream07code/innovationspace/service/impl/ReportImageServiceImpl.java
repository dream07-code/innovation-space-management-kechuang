package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.report.ReportImageCompressor;
import io.github.dream07code.innovationspace.security.RateLimitService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.ReportImageService;
import io.github.dream07code.innovationspace.storage.ReportImageStorage;
import io.github.dream07code.innovationspace.storage.ReportImageStorageProperties;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 笔记图片上传：只放行真实图片字节，其余一律按非法请求拒绝。
 *
 * <p>类型判定以文件头魔数为准而不是客户端声明的 Content-Type——浏览器可以随便声明，
 * 而对象存储里的文件是会被直接 {@code <img src>} 加载的，放行非图片等于开放任意内容托管。
 */
@Service
public class ReportImageServiceImpl implements ReportImageService {
  private static final Logger LOG = LoggerFactory.getLogger(ReportImageServiceImpl.class);
  /** 单张上限，与 application.yml 的 multipart 限制保持一致。 */
  static final int MAX_BYTES = ReportImageService.MAX_BYTES;
  /** 一次日报最多 10 张，留一倍余量给删掉重传。 */
  private static final int UPLOADS_PER_MINUTE = 20;
  private static final int NAME_MAX = 120;
  private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

  private final ReportImageStorage storage;
  private final ReportImageStorageProperties properties;
  private final RateLimitService rateLimit;
  private final Clock clock;
  private final ReportImageCompressor compressor = new ReportImageCompressor();

  public ReportImageServiceImpl(
      ReportImageStorage storage,
      ReportImageStorageProperties properties,
      RateLimitService rateLimit,
      Clock clock) {
    this.storage = storage;
    this.properties = properties;
    this.rateLimit = rateLimit;
    this.clock = clock;
  }

  @Override
  public Map<String, Object> upload(MultipartFile file, SessionPrincipal actor) {
    if (file == null || file.isEmpty()) throw bad();
    if (file.getSize() > MAX_BYTES) throw bad();

    // 先判定内容再判定存储：非法内容无论存储是否可用都是 400，
    // 否则「存储没配好」会把「这根本不是图片」这类问题掩盖掉。
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (Exception e) {
      throw bad();
    }
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw bad();
    String contentType = sniff(bytes);
    if (contentType == null) throw bad();

    rateLimit.check("report-image:" + actor.userId(), UPLOADS_PER_MINUTE, 60);
    if (!storage.available()) throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE);

    // 落盘前统一压缩：手机原图常见 200–300KB，压到一百来 KB 既省空间也快。
    var compressed = compressor.compress(bytes);
    byte[] stored = compressed == null ? bytes : compressed.bytes();
    String storedType = compressed == null ? contentType : "image/jpeg";

    String key = objectKey(actor.userId(), storedType);
    String url = storage.upload(stored, storedType, key);

    if (compressed == null) {
      // ImageIO 解不了的格式（例如 WebP）按原样存储，不能让上传直接失败。
      LOG.warn(
          "report image kept as-is (not decodable): type={} original={}B key={}",
          contentType,
          bytes.length,
          key);
    } else {
      LOG.info(
          "report image compressed: {}x{} -> {}x{}, {}B -> {}B ({}%), key={}",
          compressed.sourceWidth(),
          compressed.sourceHeight(),
          compressed.width(),
          compressed.height(),
          bytes.length,
          stored.length,
          stored.length * 100 / Math.max(1, bytes.length),
          key);
    }

    var out = new LinkedHashMap<String, Object>();
    out.put("url", url);
    out.put("name", safeName(file.getOriginalFilename(), storedType));
    out.put("size", stored.length);
    out.put("contentType", storedType);
    return out;
  }

  /** 对象键按 业务前缀/年/月/用户 归档，便于按人排查和配置最小权限策略。 */
  private String objectKey(long userId, String contentType) {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(BUSINESS_ZONE);
    String prefix = properties.getKeyPrefix().isBlank() ? "reports" : properties.getKeyPrefix();
    String month = now.getMonthValue() < 10 ? "0" + now.getMonthValue() : String.valueOf(now.getMonthValue());
    return prefix
        + "/"
        + now.getYear()
        + "/"
        + month
        + "/"
        + userId
        + "/"
        + UUID.randomUUID().toString().replace("-", "")
        + extension(contentType);
  }

  private static String extension(String contentType) {
    return switch (contentType) {
      case "image/jpeg" -> ".jpg";
      case "image/png" -> ".png";
      default -> ".webp";
    };
  }

  /** 只允许 JPEG / PNG / WebP，靠文件头判定。 */
  static String sniff(byte[] b) {
    if (b.length >= 3
        && (b[0] & 0xff) == 0xFF
        && (b[1] & 0xff) == 0xD8
        && (b[2] & 0xff) == 0xFF) return "image/jpeg";
    if (b.length >= 8
        && (b[0] & 0xff) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && (b[4] & 0xff) == 0x0D
        && (b[5] & 0xff) == 0x0A
        && (b[6] & 0xff) == 0x1A
        && (b[7] & 0xff) == 0x0A) return "image/png";
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') return "image/webp";
    return null;
  }

  /** 展示用文件名：去掉路径、控制字符并截断，绝不参与对象键拼接。 */
  private static String safeName(String original, String contentType) {
    String name = original == null ? "" : original;
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) name = name.substring(slash + 1);
    name = name.replaceAll("[\\p{Cntrl}]", "").trim();
    if (name.length() > NAME_MAX) name = name.substring(0, NAME_MAX);
    return name.isBlank() ? "note" + extension(contentType) : name;
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
