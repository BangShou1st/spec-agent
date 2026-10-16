package com.specagent.modelsettings;

import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 文件名:OpenCodeSettingsIntegrationTest.java
 *
 * 测试目标:验证免费模型可无 Key 保存并始终使用 public;付费模型需要 Key;
 * 候选 Key 优先由付费模型验证;已保存 Key 可列出模型并免重提交地切换模型。
 *
 * <p>模型目录在这里被替换为 Mockito 桩:选择列表来自
 * {@code listAvailableModels}(只含协议已验证为 Chat Completions 的模型),
 * 免费子集来自 {@code listFreeModels}——即服务层只消费"已验证"的目录事实,
 * 协议门禁本身的判定由 {@code OpenCodeModelCatalogTest} 与
 * {@code OpenCodeModelProtocolGateIntegrationTest} 覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenCodeSettingsIntegrationTest {

    @Autowired
    private OpenCodeSettingsService service;

    @Autowired
    private OpenCodeSettingsRepository repository;

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
    void probeValidatesCandidateAgainstPaidModelWithoutPersistingIt() {
        when(catalog.listAvailableModels("candidate-key"))
                .thenReturn(List.of("alpha-free", "beta-free", "paid-model"));
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("candidate-key", "paid-model");

        assertThat(service.status()).isEqualTo(OpenCodeSettingsStatus.unconfigured());
        var discovered = service.probe("candidate-key");
        assertThat(discovered.allModels()).containsExactly("alpha-free", "beta-free", "paid-model");
        assertThat(discovered.freeModels()).containsExactly("alpha-free", "beta-free");
        assertThat(discovered.recommendedProbeModel()).isEqualTo("paid-model");
        assertThat(service.status()).isEqualTo(OpenCodeSettingsStatus.unconfigured());
    }

    @Test
    void paidModelSaveRequiresAndPersistsKeyAtomically() {
        when(catalog.listAvailableModels("candidate-key")).thenReturn(List.of("alpha-free", "paid-model"));
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("candidate-key", "paid-model");
        when(catalog.credentialForModel("paid-model", "candidate-key")).thenReturn("candidate-key");

        OpenCodeSettingsStatus saved = service.save("candidate-key", "paid-model");

        assertThat(saved.configured()).isTrue();
        assertThat(saved.maskedKey()).isEqualTo("••••-key");
        assertThat(saved.selectedModel()).isEqualTo("paid-model");
        assertThat(saved.toString()).doesNotContain("candidate-key");
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("candidate-key", "paid-model"));
    }

    @Test
    void freeModelCanBeSavedWithoutKeyAndUsesPublicCredential() {
        when(catalog.listAvailableModels(null)).thenReturn(List.of("alpha-free", "paid-model"));
        when(catalog.listFreeModels(null)).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("public", "alpha-free");
        when(catalog.credentialForModel("alpha-free", null)).thenReturn("public");

        OpenCodeSettingsStatus saved = service.save(null, "alpha-free");

        assertThat(saved.configured()).isTrue();
        assertThat(saved.maskedKey()).isNull();
        assertThat(saved.selectedModel()).isEqualTo("alpha-free");
        assertThat(repository.find().orElseThrow().apiKey()).isNull();
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("public", "alpha-free"));
    }

    @Test
    void freeModelNeverReceivesAStoredPaidKey() {
        when(catalog.listAvailableModels("stored-key")).thenReturn(List.of("alpha-free", "paid-model"));
        when(catalog.listFreeModels("stored-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("public", "alpha-free");
        when(catalog.credentialForModel("alpha-free", "stored-key")).thenReturn("public");

        service.save("stored-key", "alpha-free");

        assertThat(repository.find().orElseThrow().apiKey()).isEqualTo("stored-key");
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("public", "alpha-free"));
    }

    @Test
    void paidModelCannotBeSavedWithoutKey() {
        when(catalog.listAvailableModels(null)).thenReturn(List.of("alpha-free", "paid-model"));
        when(catalog.listFreeModels(null)).thenReturn(List.of("alpha-free"));

        assertThatThrownBy(() -> service.save(null, "paid-model"))
                .isInstanceOf(com.specagent.model.provider.OpenCodeModelException.class)
                .hasMessageContaining("required for paid");
        assertThat(repository.find()).isEmpty();
    }

    @Test
    void modelListCanBeLoadedBeforeAnyKeyIsConfigured() {
        when(catalog.listAvailableModels(null)).thenReturn(List.of("alpha-free", "paid-model"));
        when(catalog.listFreeModels(null)).thenReturn(List.of("alpha-free"));

        assertThat(service.listModels().allModels()).containsExactly("alpha-free", "paid-model");
        assertThat(service.status()).isEqualTo(OpenCodeSettingsStatus.unconfigured());
    }

    @Test
    void invalidModelLeavesPreviousWorkingSettingsUntouched() {
        when(catalog.listAvailableModels("first-key")).thenReturn(List.of("paid-model"));
        when(catalog.listFreeModels("first-key")).thenReturn(List.of());
        doNothing().when(transport).validateCredential("first-key", "paid-model");
        service.save("first-key", "paid-model");
        when(catalog.credentialForModel("paid-model", "first-key")).thenReturn("first-key");

        when(catalog.listAvailableModels("second-key")).thenReturn(List.of("another-paid"));
        when(catalog.listFreeModels("second-key")).thenReturn(List.of());

        assertThatThrownBy(() -> service.save("second-key", "paid-model"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("first-key", "paid-model"));
    }

    @Test
    void savedKeyListsModelsAndChangesModelWithoutBeingSubmittedAgain() {
        when(catalog.listAvailableModels("saved-key")).thenReturn(List.of("paid-alpha", "paid-beta"));
        when(catalog.listFreeModels("saved-key")).thenReturn(List.of());
        doNothing().when(transport).validateCredential("saved-key", "paid-alpha");
        service.save("saved-key", "paid-alpha");
        when(catalog.credentialForModel("paid-beta", "saved-key")).thenReturn("saved-key");
        OpenCodeSettings before = repository.find().orElseThrow();

        assertThat(service.listModels().allModels()).containsExactly("paid-alpha", "paid-beta");

        doNothing().when(transport).validateCredential("saved-key", "paid-beta");
        OpenCodeSettingsStatus changed = service.changeModel("paid-beta");

        assertThat(changed.selectedModel()).isEqualTo("paid-beta");
        OpenCodeSettings after = repository.find().orElseThrow();
        assertThat(after.apiKey()).isEqualTo(before.apiKey());
        assertThat(after.maskedSuffix()).isEqualTo(before.maskedSuffix());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.updatedAt()).isAfterOrEqualTo(before.updatedAt());
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("saved-key", "paid-beta"));
    }

    @Test
    void failedSavedKeyModelChangePreservesTheExistingConfiguration() {
        when(catalog.listAvailableModels("saved-key")).thenReturn(List.of("paid-alpha"));
        when(catalog.listFreeModels("saved-key")).thenReturn(List.of());
        doNothing().when(transport).validateCredential("saved-key", "paid-alpha");
        service.save("saved-key", "paid-alpha");
        when(catalog.credentialForModel("paid-alpha", "saved-key")).thenReturn("saved-key");

        when(catalog.listAvailableModels("saved-key")).thenReturn(List.of("paid-beta"));
        when(catalog.listFreeModels("saved-key")).thenReturn(List.of());
        assertThatThrownBy(() -> service.changeModel("paid-alpha"))
                .isInstanceOf(RuntimeException.class);

        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("saved-key", "paid-alpha"));
    }
}
