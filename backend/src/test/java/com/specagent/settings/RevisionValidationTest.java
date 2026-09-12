package com.specagent.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.ResponsesProtocolAdapter;
import com.specagent.model.provider.AnthropicMessagesProtocolAdapter;
import com.specagent.settings.custom.CustomProviderSettings;
import com.specagent.settings.custom.CustomProviderSettingsRepository;
import com.specagent.settings.custom.CustomProviderSettingsService;
import com.specagent.settings.openrouter.OpenRouterSettings;
import com.specagent.settings.openrouter.OpenRouterSettingsRepository;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RevisionValidationTest {

    static class MemOpenRouterRepo implements OpenRouterSettingsRepository {
        OpenRouterSettings stored;
        public Optional<OpenRouterSettings> find() {
            return Optional.ofNullable(stored);
        }
        public void upsert(OpenRouterSettings s) {
            stored = s;
        }
        public void markValidated(long rev) {
            stored = new OpenRouterSettings(stored.apiKey(), stored.maskedSuffix(), stored.selectedModel(),
                    stored.configRevision(), rev, stored.createdAt(), stored.updatedAt(), Instant.now());
        }
    }

    static class MemCustomRepo implements CustomProviderSettingsRepository {
        CustomProviderSettings stored;
        public Optional<CustomProviderSettings> find() {
            return Optional.ofNullable(stored);
        }
        public void upsert(CustomProviderSettings s) {
            stored = s;
        }
        public void markValidated(long rev) {
            stored = new CustomProviderSettings(stored.apiFormat(), stored.baseUrl(), stored.apiKey(),
                    stored.maskedSuffix(), stored.selectedModel(), stored.modelSource(), stored.configRevision(), rev,
                    stored.createdAt(), stored.updatedAt(), Instant.now());
        }
    }

    private ProtocolAdapterRegistry registry() {
        return new ProtocolAdapterRegistry(List.of(
                new ChatCompletionsProtocolAdapter(),
                new ResponsesProtocolAdapter(),
                new AnthropicMessagesProtocolAdapter()));
    }

    private CompatibilityProbeService noopProbe() {
        return new CompatibilityProbeService(new ObjectMapper(), registry(),
                new GlobalAssistantDecisionParser(new ObjectMapper()),
                new GlobalAssistantDecisionValidator()) {
            @Override
            public void probeOpenRouter(String apiKey, String model) {
            }
            @Override
            public void probeCustom(CustomApiFormat format, String base, String key, String model) {
            }
        };
    }

    @Test void openrouterChangeInvalidatesAndRevalidateAllows() {
        MemOpenRouterRepo repo = new MemOpenRouterRepo();
        Instant now = Instant.now();
        repo.stored = new OpenRouterSettings("k1", "k1", "m:free", 1, 1L, now, now, now);
        // Simulate save with changed model via direct repo bump (service.save does live discovery,
        // so revision semantics are asserted at the repository + gate level here).
        repo.stored = new OpenRouterSettings("k1", "k1", "m2:free", 2, null, now, now, null);
        OpenRouterSettingsService svc = new OpenRouterSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
        assertThatThrownBy(svc::requireActivatable)
                .isInstanceOf(ModelProviderException.class);
        repo.markValidated(2);
        svc.requireActivatable();
    }

    @Test void customChangeInvalidates() {
        MemCustomRepo repo = new MemCustomRepo();
        Instant now = Instant.now();
        repo.stored = new CustomProviderSettings("CHAT_COMPLETIONS", "http://localhost:11434/v1", null, null, "m", "DISCOVERED", 1, 1L, now, now, now);
        CustomProviderSettingsService svc = new CustomProviderSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
        svc.requireActivatable();
        // Change baseUrl bumps revision and clears validation.
        repo.stored = new CustomProviderSettings("CHAT_COMPLETIONS", "http://localhost:11435/v1", null, null, "m", "DISCOVERED", 2, null, now, now, null);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
        // Change format also invalidates.
        repo.stored = new CustomProviderSettings("RESPONSES", "http://localhost:11435/v1", null, null, "m", "DISCOVERED", 3, null, now, now, null);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
        repo.markValidated(3);
        svc.requireActivatable();
    }

    @Test void anthropicNeitherValidatesNorActivates() {
        MemCustomRepo repo = new MemCustomRepo();
        Instant now = Instant.now();
        repo.stored = new CustomProviderSettings("ANTHROPIC_MESSAGES", "https://gateway.example/v1",
                null, null, "m", "DISCOVERED", 1, null, now, now, null);
        CustomProviderSettingsService svc = new CustomProviderSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
        assertThatThrownBy(svc::validate).isInstanceOf(ModelProviderException.class);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
    }
}
