package com.specagent.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API error contract integration tests: stable validation responses, safe
 * malformed-UUID handling, and a generic 500 that never exposes a stack trace
 * or internal message. The throwaway probe controller is registered only for
 * this test and triggers an unexpected failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorHandlingIntegrationTest {

    private static final String PROBE_SECRET = "probe-secret-7f21";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unexpectedErrorDoesNotExposeStacktraceOrInternalMessage() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected internal error occurred"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(PROBE_SECRET)
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.specagent")
                .doesNotContain("stacktrace");
    }

    @Test
    void validationErrorShapeIsStable() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UUID"))
                .andExpect(jsonPath("$.message").value("Path or query argument must be a valid UUID"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe/unexpected")
        public String boom() {
            throw new IllegalStateException(PROBE_SECRET);
        }
    }
}