package io.github.dream07code.testsupport;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@TestConfiguration(proxyBeanMethods = false)
@Import(WebProbeConfiguration.ProbeController.class)
public class WebProbeConfiguration {
    public record ProbeRequest(
            @NotBlank @Schema(description = "测试名称", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Size(min = 8) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) { }

    @RestController
    @RequestMapping("/api/v1/test-probe")
    @Tag(name = "仅测试探针")
    public static class ProbeController {
        @GetMapping("/ping")
        @Operation(summary = "测试连通性")
        public ApiResponse<String> ping() {
            return ApiResponse.success("pong");
        }

        @PostMapping(value = "/validate", consumes = "application/json")
        @Operation(summary = "测试参数校验")
        @ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数不合法")
        })
        public ApiResponse<String> validate(@Valid @RequestBody ProbeRequest request) {
            return ApiResponse.success(request.name());
        }

        @GetMapping("/failure")
        @Operation(summary = "测试安全异常响应")
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "服务内部错误，不返回敏感信息")
        public ApiResponse<Void> failure() {
            throw new IllegalStateException("upstream failure secret7");
        }

        @GetMapping("/invalid-detail")
        @Operation(summary = "测试 400 排查细节只进日志")
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数不合法")
        public ApiResponse<Void> invalidDetail() {
            throw new io.github.dream07code.innovationspace.common.exception.ApiException(
                    400,
                    io.github.dream07code.innovationspace.common.api.ErrorCode.INVALID_REQUEST,
                    "unsupported fields [directionId]");
        }

        @GetMapping("/query")
        public ApiResponse<Integer> query(@RequestParam @Min(1) int count) {
            return ApiResponse.success(count);
        }
    }
}
