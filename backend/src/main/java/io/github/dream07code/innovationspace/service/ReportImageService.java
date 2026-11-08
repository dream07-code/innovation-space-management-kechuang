package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/** 日报/周报笔记图片上传。返回的 URL 直接存进报告内容，随报告版本一起固化。 */
public interface ReportImageService {
  /** 单张图片字节上限；上传校验与报告内容校验共用同一口径。 */
  int MAX_BYTES = 10 * 1024 * 1024;

  /** 一篇日报/周报最多携带的图片张数。 */
  int MAX_IMAGES = 10;

  /**
   * 校验并上传一张图片。
   *
   * @return {@code url / name / size / contentType}，前端原样放进 {@code content.images}
   */
  Map<String, Object> upload(MultipartFile file, SessionPrincipal actor);
}
