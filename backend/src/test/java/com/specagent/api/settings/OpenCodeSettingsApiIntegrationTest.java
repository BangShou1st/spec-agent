package com.specagent.api.settings;

import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenCodeSettingsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockBean
    private OpenCodeModelCatalog catalog;

    @MockBean
    private OpenCodeZenTransport transport;

    @BeforeEach
    void clearSettings() {
        jdbc.getJdbcTemplate().update("DELETE FROM opencode_settings");
    }

    @Test
    void statusNeverReturnsTheKeyAndProbeDoesNotPersist() throws Exception {
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

        mockMvc.perform(get("/api/v1/settings/opencode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("apiKey"))));

        mockMvc.perform(post("/api/v1/settings/opencode/probe")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"candidate-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeModels").isArray())
                .andExpect(jsonPath("$.freeModels[0]").value("alpha-free"));

        mockMvc.perform(get("/api/v1/settings/opencode"))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void saveRequiresCurrentFreeModelAndReturnsOnlySafeProjection() throws Exception {
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

        mockMvc.perform(put("/api/v1/settings/opencode")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"candidate-key\",\"selectedModel\":\"alpha-free\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedKey").value("••••-key"))
                .andExpect(jsonPath("$.selectedModel").value("alpha-free"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("candidate-key"))));
    }

    @Test
    void rateLimitIsMappedWithoutReplacingExistingSettings() throws Exception {
        when(catalog.listFreeModels("working-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("working-key", "alpha-free");
        mockMvc.perform(put("/api/v1/settings/opencode")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"working-key\",\"selectedModel\":\"alpha-free\"}"))
                .andExpect(status().isOk());

        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free"));
        doThrow(new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                "provider limited the request", 429))
                .when(transport).validateCredential("candidate-key", "alpha-free");

        mockMvc.perform(post("/api/v1/settings/opencode/probe")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"candidate-key\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_RATE_LIMITED"));

        mockMvc.perform(get("/api/v1/settings/opencode"))
                .andExpect(jsonPath("$.selectedModel").value("alpha-free"));
    }
}
