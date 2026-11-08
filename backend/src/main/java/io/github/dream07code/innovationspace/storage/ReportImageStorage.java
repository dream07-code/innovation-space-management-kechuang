package io.github.dream07code.innovationspace.storage;

/**
 * 日报/周报图片的对象存储。
 *
 * <p>只负责把字节写到远端并返回可公开访问的 URL；配额、类型、归属校验都在
 * {@code ReportImageService} 里做，存储实现保持纯粹。
 */
public interface ReportImageStorage {

  /** 存储是否已配置好可用凭据。未配置时上传接口应给出明确提示而不是 500。 */
  boolean available();

  /**
   * 上传一张图片。
   *
   * @param content 图片字节（已通过类型与大小校验）
   * @param contentType 规范化的 MIME 类型，如 {@code image/jpeg}
   * @param key 对象键，形如 {@code reports/2026/11/<uuid>.jpg}
   * @return 可直接用于 {@code <img src>} 的公开 URL
   */
  String upload(byte[] content, String contentType, String key);
}
