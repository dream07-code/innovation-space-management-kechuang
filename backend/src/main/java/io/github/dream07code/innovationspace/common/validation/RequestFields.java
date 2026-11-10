package io.github.dream07code.innovationspace.common.validation;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * 写接口的字段结构校验。
 *
 * <p>各接口原先各自写 {@code !Set.of(...).equals(request.keySet())}，报错只有一句
 * "Invalid request"，调用方不知道是哪个字段出了问题（多传了一个、还是某字段取值不合法）。
 * 这里统一成「点名到字段」的校验：多传字段 → unknown field: X；必填缺失 → missing field: X；
 * 数值越界 → 说明是哪一项、合法区间是什么。
 *
 * <p>错误信息里只写字段名与合法范围，不回显请求里的取值（契约要求不通过错误信息泄露输入值）。
 * 错误码仍然是 {@link ErrorCode#INVALID_REQUEST}，校验强度不变。
 */
public final class RequestFields {
  private RequestFields() {}

  /** 只允许给定字段：多带任何字段都拒绝，并指出是哪个字段。 */
  public static void rejectUnknown(Map<String, Object> request, Set<String> allowed) {
    for (String key : request.keySet())
      if (!allowed.contains(key))
        throw invalid("unknown field: " + key);
  }

  /** 必填字段缺失时点名。 */
  public static void requirePresent(Map<String, Object> request, String... names) {
    for (String name : names)
      if (!request.containsKey(name)) throw invalid("missing field: " + name);
  }

  /** 整数字段且落在 [min,max]；说明是哪一项、合法区间是什么，不回显取值。 */
  public static long integerInRange(Object value, String field, long min, long max) {
    if (!(value instanceof Number number) || number.doubleValue() != (double) number.longValue())
      throw invalid(field + " must be an integer");
    long actual = number.longValue();
    if (actual < min || actual > max)
      throw invalid(field + " must be between " + min + " and " + max);
    return actual;
  }

  private static ApiException invalid(String detail) {
    return new ApiException(400, ErrorCode.INVALID_REQUEST, detail);
  }
}
