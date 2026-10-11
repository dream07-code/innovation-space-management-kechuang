package io.github.dream07code.innovationspace.common.exception;

import io.github.dream07code.innovationspace.common.api.ErrorCode;

public final class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final int status;
    public ApiException(int status, ErrorCode code){super(code.message());this.status=status;this.code=code;}
    public ErrorCode code(){return code;}
    public int status(){return status;}
}
