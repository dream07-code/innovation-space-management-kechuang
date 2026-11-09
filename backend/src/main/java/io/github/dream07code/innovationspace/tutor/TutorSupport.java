package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 助教相关响应里共用的格式化规则：时间统一东八区带偏移，标题统一折叠空白并截断。 */
public final class TutorSupport {
  /** 与批改报告/助教对话已有的时间格式保持一致，前端可以直接展示。 */
  public static final DateTimeFormatter OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  public static final int MAX_TITLE = 120;
  private static final int AUTO_TITLE_CHARS = 30;

  private TutorSupport() {}

  public static String time(Instant value) {
    return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).format(OFFSET);
  }

  /**
   * 用第一句话做会话标题：折叠换行与连续空格，取前 30 个字。
   *
   * <p>标题只由学生自己发的内容生成，不调用模型取名，避免多一次计费与不可复现的标题。
   */
  public static String titleFrom(String message) {
    String flat = message == null ? "" : message.replaceAll("\\s+", " ").trim();
    if (flat.isEmpty()) return "新对话";
    if (flat.length() <= AUTO_TITLE_CHARS) return flat;
    return flat.substring(0, AUTO_TITLE_CHARS) + "…";
  }

  /** 学生手填的标题：去掉首尾空白与控制字符，过长按数据库上限截断。 */
  public static String cleanTitle(String title) {
    String flat = title == null ? "" : title.replaceAll("[\\r\\n\\t]+", " ").trim();
    if (flat.isEmpty()) return null;
    return flat.length() <= MAX_TITLE ? flat : flat.substring(0, MAX_TITLE);
  }

  public static boolean isId(String value) {
    return value != null && value.matches("[1-9][0-9]{0,18}");
  }

  public static String lower(Object value) {
    return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
  }
}
