package io.github.dream07code.innovationspace.common.api;

public enum ErrorCode {
    INVALID_REQUEST("Invalid request"),
    NOT_FOUND("Resource not found"),
    METHOD_NOT_ALLOWED("Method not allowed"),
    SERVICE_UNAVAILABLE("Service unavailable"),
    UNAUTHORIZED("Authentication required"),
    INVALID_CREDENTIALS("Invalid credentials"),
    REFRESH_TOKEN_INVALID("Refresh token invalid"),
    FORBIDDEN("Forbidden"),
    PASSWORD_CHANGE_REQUIRED("Password change required"),
    ACCOUNT_DISABLED("Account disabled"),
    MEMBER_REMOVED("Member removed"),
    OLD_PASSWORD_INVALID("Old password invalid"),
    PASSWORD_POLICY_VIOLATION("Password policy violation"),
    VERSION_CONFLICT("Version conflict"),
    ACCOUNT_ALREADY_EXISTS("Account already exists"),
    INVALID_STATE("Invalid state"),
    IDEMPOTENCY_CONFLICT("Idempotency conflict"),
    RATE_LIMITED("Rate limited"),
    INTERNAL_ERROR("Internal server error");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
