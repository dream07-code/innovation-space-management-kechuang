package io.github.dream07code.innovationspace.common;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTest {
    @Test
    void successContainsThePayloadAndStableSuccessCode() {
        var response = ApiResponse.success("pong");
        assertEquals("OK", response.code());
        assertEquals("Success", response.message());
        assertEquals("pong", response.data());
    }

    @Test
    void invalidRequestContainsOnlyThePublicMessageAndNoPayload() {
        var response = ApiResponse.failure(ErrorCode.INVALID_REQUEST);
        assertEquals("INVALID_REQUEST", response.code());
        assertEquals("Invalid request", response.message());
        assertNull(response.data());
    }

    @Test
    void unexpectedFailureHasAGenericMessage() {
        var response = ApiResponse.failure(ErrorCode.INTERNAL_ERROR);
        assertEquals("INTERNAL_ERROR", response.code());
        assertEquals("Internal server error", response.message());
        assertNull(response.data());
    }
}
