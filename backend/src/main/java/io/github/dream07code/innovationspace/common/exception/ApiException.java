package io.github.dream07code.innovationspace.common.exception;

import io.github.dream07code.innovationspace.common.api.ErrorCode;

public final class ApiException extends RuntimeException {
  private final ErrorCode code;
  private final int status;

  public ApiException(int status, ErrorCode code) {
    super(code.message());
    this.status = status;
    this.code = code;
  }

  /**
   * 带排查细节的版本：细节会随 INVALID_REQUEST 一起回给调用方。
   *
   * <p>响应 message 由 {@code GlobalExceptionHandler} 取这里的 message；
   * 因此细节里只放字段名与合法范围，绝不放请求里的取值
   * （契约要求"不通过错误信息回显输入值"）。
   */
  public ApiException(int status, ErrorCode code, String detail) {
    super(code.message() + ": " + detail);
    this.status = status;
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }

  public int status() {
    return status;
  }
}
