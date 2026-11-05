package io.github.dream07code.innovationspace.common;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 400 的排查细节只进日志，不进响应体。
 *
 * 这条边界很重要：加了 detail 之后，接口文案必须还是枚举里的通用文案，
 * 否则会通过错误信息回显字段名/结构，等于把契约里的"通用错误信息"要求破掉。
 */
class ApiExceptionTest {
    @Test
    void detailIsKeptForTheLog() {
        var exception = new ApiException(400, ErrorCode.INVALID_REQUEST, "unsupported fields [directionId]");
        assertEquals(400, exception.status());
        assertEquals(ErrorCode.INVALID_REQUEST, exception.code());
        assertTrue(exception.getMessage().contains("unsupported fields [directionId]"));
    }

    @Test
    void publicMessageStaysGenericEvenWithDetail() {
        var exception = new ApiException(400, ErrorCode.INVALID_REQUEST, "items[3].score must be an integer in 1..100");
        var response = ApiResponse.failure(exception.code());
        assertEquals("INVALID_REQUEST", response.code());
        assertEquals("Invalid request", response.message());
        assertEquals(ApiResponse.failure(ErrorCode.INVALID_REQUEST), response);
    }

    @Test
    void defaultConstructorStillUsesTheEnumMessage() {
        var exception = new ApiException(400, ErrorCode.INVALID_REQUEST);
        assertEquals("Invalid request", exception.getMessage());
    }
}
