package io.github.dream07code.innovationspace.common.api;

public record ApiResponse<T>(String code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "Success", data);
    }

    public static ApiResponse<Void> failure(ErrorCode error) {
        return new ApiResponse<>(error.name(), error.message(), null);
    }
}
