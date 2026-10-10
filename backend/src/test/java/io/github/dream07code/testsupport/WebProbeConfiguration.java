package io.github.dream07code.testsupport;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
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
    public record ProbeRequest(@NotBlank String name, @Size(min = 8) String password) { }

    @RestController
    @RequestMapping("/api/v1/test-probe")
    public static class ProbeController {
        @GetMapping("/ping")
        public ApiResponse<String> ping() {
            return ApiResponse.success("pong");
        }

        @PostMapping(value = "/validate", consumes = "application/json")
        public ApiResponse<String> validate(@Valid @RequestBody ProbeRequest request) {
            return ApiResponse.success(request.name());
        }

        @GetMapping("/failure")
        public ApiResponse<Void> failure() {
            throw new IllegalStateException("upstream failure secret7");
        }

        @GetMapping("/query")
        public ApiResponse<Integer> query(@RequestParam @Min(1) int count) {
            return ApiResponse.success(count);
        }
    }
}
