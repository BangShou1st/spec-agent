package com.specagent.settings.opencode;

import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
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
    void probeDiscoversFreeModelsWithoutPersistingCandidate() {
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

        assertThat(service.status()).isEqualTo(OpenCodeSettingsStatus.unconfigured());
        assertThat(service.probe("candidate-key")).containsExactly("alpha-free", "beta-free");
        assertThat(service.status()).isEqualTo(OpenCodeSettingsStatus.unconfigured());
    }

    @Test
    void saveRevalidatesAndPersistsKeyAndModelAtomically() {
        when(catalog.listFreeModels("candidate-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("candidate-key", "alpha-free");

        OpenCodeSettingsStatus saved = service.save("candidate-key", "alpha-free");

        assertThat(saved.configured()).isTrue();
        assertThat(saved.maskedKey()).isEqualTo("••••-key");
        assertThat(saved.selectedModel()).isEqualTo("alpha-free");
        assertThat(saved.toString()).doesNotContain("candidate-key");
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("candidate-key", "alpha-free"));
    }

    @Test
    void invalidModelLeavesPreviousWorkingSettingsUntouched() {
        when(catalog.listFreeModels("first-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("first-key", "alpha-free");
        service.save("first-key", "alpha-free");

        when(catalog.listFreeModels("second-key")).thenReturn(List.of("beta-free"));

        assertThatThrownBy(() -> service.save("second-key", "alpha-free"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("first-key", "alpha-free"));
    }

    @Test
    void savedKeyListsModelsAndChangesModelWithoutBeingSubmittedAgain() {
        when(catalog.listFreeModels("saved-key")).thenReturn(List.of("alpha-free", "beta-free"));
        doNothing().when(transport).validateCredential("saved-key", "alpha-free");
        service.save("saved-key", "alpha-free");
        OpenCodeSettings before = repository.find().orElseThrow();

        assertThat(service.listSavedKeyModels()).containsExactly("alpha-free", "beta-free");

        doNothing().when(transport).validateCredential("saved-key", "beta-free");
        OpenCodeSettingsStatus changed = service.changeModel("beta-free");

        assertThat(changed.selectedModel()).isEqualTo("beta-free");
        OpenCodeSettings after = repository.find().orElseThrow();
        assertThat(after.apiKey()).isEqualTo(before.apiKey());
        assertThat(after.maskedSuffix()).isEqualTo(before.maskedSuffix());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.updatedAt()).isAfterOrEqualTo(before.updatedAt());
        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("saved-key", "beta-free"));
    }

    @Test
    void failedSavedKeyModelChangePreservesTheExistingConfiguration() {
        when(catalog.listFreeModels("saved-key")).thenReturn(List.of("alpha-free"));
        doNothing().when(transport).validateCredential("saved-key", "alpha-free");
        service.save("saved-key", "alpha-free");

        when(catalog.listFreeModels("saved-key")).thenReturn(List.of("beta-free"));
        assertThatThrownBy(() -> service.changeModel("alpha-free"))
                .isInstanceOf(RuntimeException.class);

        assertThat(service.requireRuntimeSettings()).isEqualTo(
                new RuntimeOpenCodeSettings("saved-key", "alpha-free"));
    }
}
