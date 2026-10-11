package io.github.dream07code.innovationspace.web;

import io.github.dream07code.innovationspace.common.exception.GlobalExceptionHandler;
import io.github.dream07code.testsupport.WebProbeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers=WebProbeConfiguration.ProbeController.class, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes = {io.github.dream07code.innovationspace.config.SchemaReadinessConfiguration.class,
                io.github.dream07code.innovationspace.web.AuthController.class,
                io.github.dream07code.innovationspace.security.SecurityConfiguration.class,
                io.github.dream07code.innovationspace.config.SchemaReadinessInterceptor.class}))
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters=false)
@Import({WebProbeConfiguration.class, GlobalExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void validRequestKeepsTheResponseEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/test-probe/validate")
                .contentType(APPLICATION_JSON).content("{broken"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void blankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/test-probe/validate")
                .contentType(APPLICATION_JSON).content("{\"name\":\" \",\"password\":\"not-a-real-password\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shortPasswordIsNotEchoed(CapturedOutput output) throws Exception {
        var response = mockMvc.perform(post("/api/v1/test-probe/validate")
                .contentType(APPLICATION_JSON).content("{\"name\":\"probe\",\"password\":\"secret7\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret7", "trace", "rejectedValue");
        assertThat(output.getAll()).doesNotContain("secret7");
    }

    @Test
    void invalidQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/query").param("count", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void queryTypeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/query").param("count", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodReturns405AndPreservesAllow() throws Exception {
        mockMvc.perform(post("/api/v1/test-probe/ping"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/test-probe/validate").contentType(TEXT_PLAIN).content("text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unexpectedFailureIsSanitized(CapturedOutput output) throws Exception {
        var response = mockMvc.perform(get("/api/v1/test-probe/failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Internal server error"))
            .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret7", "IllegalStateException", "trace");
        assertThat(output.getAll()).doesNotContain("secret7");
    }
}
