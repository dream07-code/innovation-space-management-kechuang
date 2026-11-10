package io.github.dream07code.innovationspace.common.api;

public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "Success", data);
    }

    public static ApiResponse<Void> failure(ErrorCode error) {
        return new ApiResponse<>(error.name(), error.message(), null);
    }

    /**
     * 带排查细节的失败响应。
     *
     * <p>目前只给 INVALID_REQUEST 用：这类错误只有一句「内容不合法」时，调用方无从判断是哪个
     * 字段出的问题。细节只写字段名与合法范围，不回显取值。
     */
    public static ApiResponse<Void> failure(ErrorCode error, String message) {
        return new ApiResponse<>(error.name(), message, null);
    }
}
