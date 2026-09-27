package com.specagent.modelsettings;

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

/**
 * 文件名:OpenCodeSettingsApiIntegrationTest.java
 *
 * 测试目标:通过 MockMvc 对 OpenCode 设置 REST API 做集成测试:状态接口永不返回
 * apiKey、probe 只做发现不持久化候选键;保存要求模型必须是当前免费列表中的模型,
 * 且响应只返回安全投影(maskedKey,不出现明文 Key);Provider 限流错误映射为 429
 * 且不替换已有设置;已保存 Key 后的 models / model 接口既不需要提交 Key 也不回显 Key。
 */
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
        when(catalog.listAllModels("candidate-key"))
                .thenReturn(List.of("alpha-free", "beta-free", "paid-model"));
        doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

        mockMvc.perform(get("/api/v1/settings/opencode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("apiKey"))));

        mockMvc.perform(post("/api/v1/settings/opencode/probe")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"candidate-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allModels").isArray())
                .andExpect(jsonPath("$.allModels.length()").value(3))
                .andExpect(jsonPath("$.freeModels").isArray())
                .andExpect(jsonPath("$.freeModels[0]").value("alpha-free"));

        mockMvc.perform(get("/api/v1/settings/opencode"))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void saveRequiresCurrentFreeModelAndReturnsOnlySafeProjection() throws Exception {
        when(catalog.listAllModels("candidate-key")).thenReturn(List.of("alpha-free"));
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
        when(catalog.listAllModels("working-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("working-key", "alpha-free");
        mockMvc.perform(put("/api/v1/settings/opencode")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"working-key\",\"selectedModel\":\"alpha-free\"}"))
                .andExpect(status().isOk());

        when(catalog.listAllModels("candidate-key")).thenReturn(List.of("alpha-free"));
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

    @Test
    void savedKeyModelEndpointsDoNotRequireTheKeyOrReturnIt() throws Exception {
        when(catalog.listAllModels("saved-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("saved-key", "alpha-free");
        mockMvc.perform(put("/api/v1/settings/opencode")
                        .contentType("application/json")
                        .content("{\"apiKey\":\"saved-key\",\"selectedModel\":\"alpha-free\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/settings/opencode/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeModels[1]").value("beta-free"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("saved-key"))));

        doNothing().when(transport).validateCredential("saved-key", "beta-free");
        mockMvc.perform(put("/api/v1/settings/opencode/model")
                        .contentType("application/json")
                        .content("{\"selectedModel\":\"beta-free\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedModel").value("beta-free"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("saved-key"))));
    }
}
