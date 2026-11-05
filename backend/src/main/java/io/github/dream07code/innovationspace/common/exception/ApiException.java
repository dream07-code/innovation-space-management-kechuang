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
   * 带排查细节的版本：细节只进日志，不进响应体。
   *
   * 响应文案由 {@code ApiResponse.failure(code)} 从枚举取，与异常 message 无关，
   * 因此这里可以放心写明非法字段名、条目下标等结构信息；调用方看到的仍是通用文案，
   * 契约要求的"不通过错误信息回显输入值"也依然成立（细节里只放字段名和下标，不放值）。
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
