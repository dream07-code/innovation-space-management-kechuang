package io.github.dream07code.innovationspace.common.api;

public enum ErrorCode {
    INVALID_REQUEST("Invalid request"),
    NOT_FOUND("Resource not found"),
    METHOD_NOT_ALLOWED("Method not allowed"),
    INTERNAL_ERROR("Internal server error");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
