package io.github.dream07code.innovationspace.report;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.service.ReportImageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告内容里图片列表的规范化与校验。
 *
 * <p>只接受本站图片存储域名下的地址：报告正文会被直接渲染成 {@code <img src>}，
 * 若允许任意外链，等于把学生日报变成可嵌入第三方跟踪资源的载体。
 */
public final class ReportImagePolicy {
  private static final Set<String> DAILY_ANCHORS =
      Set.of("learningContent", "gains", "problems", "end");
  private static final Set<String> WEEKLY_ANCHORS =
      Set.of("summary", "achievements", "reflections", "nextWeekPlan", "end");

  private final String allowedPrefix;

  public ReportImagePolicy(String domain) {
    String value = domain == null ? "" : domain.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    this.allowedPrefix = value.isEmpty() ? "" : value + "/";
  }

  /** 把请求里的图片数组规范成固定结构；非法输入一律 400。 */
  public List<Map<String, Object>> normalize(Object value, ReportType type) {
    if (value == null) return new ArrayList<>();
    if (!(value instanceof List<?> list) || list.size() > ReportImageService.MAX_IMAGES) throw bad();
    Set<String> anchors = type == ReportType.DAILY ? DAILY_ANCHORS : WEEKLY_ANCHORS;
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> item)) throw bad();
      Object rawUrl = item.get("url");
      if (!(rawUrl instanceof String url) || url.length() > 512) throw bad();
      if (allowedPrefix.isEmpty() || !url.startsWith(allowedPrefix)) throw bad();

      Object rawName = item.get("name");
      if (rawName != null && (!(rawName instanceof String name) || name.length() > 120)) throw bad();

      Object rawSize = item.get("size");
      long size = 0;
      if (rawSize != null) {
        if (!(rawSize instanceof Number number)) throw bad();
        size = number.longValue();
        if (size < 0 || size > ReportImageService.MAX_BYTES) throw bad();
      }

      Object rawAnchor = item.get("anchor");
      String anchor =
          rawAnchor == null || String.valueOf(rawAnchor).isBlank()
              ? "end"
              : String.valueOf(rawAnchor);
      if (!anchors.contains(anchor)) throw bad();

      Map<String, Object> image = new LinkedHashMap<>();
      image.put("url", url);
      image.put("name", rawName == null ? "note" : rawName);
      image.put("size", size);
      image.put("anchor", anchor);
      result.add(image);
    }
    return result;
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
