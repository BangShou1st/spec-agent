package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.ResponsesProtocolAdapter;
import com.specagent.model.provider.AnthropicMessagesProtocolAdapter;
import com.specagent.modelsettings.CustomProviderSettings;
import com.specagent.modelsettings.CustomProviderSettingsRepository;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.OpenRouterSettings;
import com.specagent.modelsettings.OpenRouterSettingsRepository;
import com.specagent.modelsettings.OpenRouterSettingsService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:RevisionValidationTest.java
 *
 * 测试目标:验证配置修订(revision)校验语义:OpenRouter / 自定义 Provider 的
 * 配置一旦变更(模型、baseUrl、协议格式),配置修订号递增并清除校验标记,
 * requireActivatable 在重新校验前 fail-closed,重新 markValidated 后放行;
 * Anthropic Messages 格式始终不可校验、也不可激活。
 */
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
                    stored.maskedSuffix(), stored.selectedModel(), stored.modelSource(), stored.displayName(),
                    stored.configRevision(), rev, stored.createdAt(), stored.updatedAt(), Instant.now());
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
                new com.specagent.model.provider.CompatibilityProbeSemantics()) {
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
        // 通过直接写 repo 模拟"换模型后的保存"(service.save 会做线上发现,
        // 所以修订语义在这里只在 repository + 门禁层面断言)。
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
        repo.stored = new CustomProviderSettings("CHAT_COMPLETIONS", "http://localhost:11434/v1", null, null, "m", "DISCOVERED", null, 1, 1L, now, now, now);
        CustomProviderSettingsService svc = new CustomProviderSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
        svc.requireActivatable();
        // 变更 baseUrl 会递增修订号并清除校验标记。
        repo.stored = new CustomProviderSettings("CHAT_COMPLETIONS", "http://localhost:11435/v1", null, null, "m", "DISCOVERED", null, 2, null, now, now, null);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
        // 变更协议格式同样使设置失效。
        repo.stored = new CustomProviderSettings("RESPONSES", "http://localhost:11435/v1", null, null, "m", "DISCOVERED", null, 3, null, now, now, null);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
        repo.markValidated(3);
        svc.requireActivatable();
    }

    @Test void anthropicNeitherValidatesNorActivates() {
        MemCustomRepo repo = new MemCustomRepo();
        Instant now = Instant.now();
        repo.stored = new CustomProviderSettings("ANTHROPIC_MESSAGES", "https://gateway.example/v1",
                null, null, "m", "DISCOVERED", null, 1, null, now, now, null);
        CustomProviderSettingsService svc = new CustomProviderSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
        assertThatThrownBy(svc::validate).isInstanceOf(ModelProviderException.class);
        assertThatThrownBy(svc::requireActivatable).isInstanceOf(ModelProviderException.class);
    }
}
