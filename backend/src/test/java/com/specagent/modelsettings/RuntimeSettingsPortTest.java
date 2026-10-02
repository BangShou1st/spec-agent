package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.OpenRouterRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.contract.RuntimeOpenRouterSettings;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ResponsesProtocolAdapter;
import com.specagent.model.provider.AnthropicMessagesProtocolAdapter;
import com.specagent.modelsettings.CustomProviderSettings;
import com.specagent.modelsettings.CustomProviderSettingsRepository;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.modelsettings.OpenCodeSettingsService;
import com.specagent.modelsettings.OpenRouterSettings;
import com.specagent.modelsettings.OpenRouterSettingsRepository;
import com.specagent.modelsettings.OpenRouterSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:RuntimeSettingsPortTest.java
 *
 * 测试目标:针对"模型自有的运行时设置端口"(用于打破 model 与 settings 包之间的循环依赖)
 * 的回归测试:各设置服务实现窄端口接口,投影保留网关消费的全部字段,
 * fail-closed 语义(未配置 / 修订号未校验)保持不变,且 Provider Key 绝不出现在
 * 投影的 toString 诊断输出中。覆盖 OpenRouter / Custom / OpenCode 三条端口。
 */
class RuntimeSettingsPortTest {

    // ---- OpenRouter -----------------------------------------------------

    @Test
    void openRouterPortFailsClosedWhenUnconfigured() {
        OpenRouterSettingsService svc = openRouterService(new MemOpenRouterRepo());
        OpenRouterRuntimeSettingsPort port = svc;

        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void openRouterPortFailsClosedWhenRevisionIsNotValidated() {
        MemOpenRouterRepo repo = new MemOpenRouterRepo();
        Instant now = Instant.now();
        // 从未在当前配置修订号下校验过。
        repo.stored = new OpenRouterSettings("or-secret-key", "e-key", "m:free",
                2, null, now, now, null);
        OpenRouterRuntimeSettingsPort port = openRouterService(repo);

        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(ModelProviderException.class);

        // 在过期修订号下校验过的同样 fail-closed。
        repo.stored = new OpenRouterSettings("or-secret-key", "e-key", "m:free",
                2, 1L, now, now, now);
        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(ModelProviderException.class);
    }

    @Test
    void openRouterPortProjectsKeyAndModelAfterValidation() {
        MemOpenRouterRepo repo = new MemOpenRouterRepo();
        Instant now = Instant.now();
        repo.stored = new OpenRouterSettings("or-secret-key", "e-key", "m:free",
                2, 2L, now, now, now);
        OpenRouterRuntimeSettingsPort port = openRouterService(repo);

        RuntimeOpenRouterSettings settings = port.requireRuntimeSettings();

        assertThat(settings.apiKey()).isEqualTo("or-secret-key");
        assertThat(settings.selectedModel()).isEqualTo("m:free");
        assertThat(settings.toString()).doesNotContain("or-secret-key");
    }

    // ---- Custom ---------------------------------------------------------

    @Test
    void customPortFailsClosedWhenUnconfigured() {
        CustomProviderSettingsService svc = customService(new MemCustomRepo());
        CustomRuntimeSettingsPort port = svc;

        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void customPortFailsClosedWhenRevisionIsNotValidated() {
        MemCustomRepo repo = new MemCustomRepo();
        Instant now = Instant.now();
        repo.stored = customSettings("CHAT_COMPLETIONS", "http://localhost:11434/v1",
                "ck-secret-key", "cm", 1, null, now);
        CustomRuntimeSettingsPort port = customService(repo);

        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(ModelProviderException.class);
    }

    @Test
    void customPortProjectsEveryGatewayFieldWithoutLeakingTheKey() {
        MemCustomRepo repo = new MemCustomRepo();
        Instant now = Instant.now();
        repo.stored = customSettings("RESPONSES", "http://localhost:11435/v1",
                "ck-secret-key", "cm", 3, 3L, now);
        CustomRuntimeSettingsPort port = customService(repo);

        RuntimeCustomSettings settings = port.requireRuntimeSettings();

        // 网关正是从这四个字段推导协议格式 + 规范端点 + 鉴权 + 模型——投影一个都不能丢。
        assertThat(settings.apiFormat()).isEqualTo("RESPONSES");
        assertThat(settings.baseUrl()).isEqualTo("http://localhost:11435/v1");
        assertThat(settings.apiKey()).isEqualTo("ck-secret-key");
        assertThat(settings.selectedModel()).isEqualTo("cm");
        assertThat(settings.toString()).doesNotContain("ck-secret-key");
    }

    // ---- OpenCode -------------------------------------------------------

    @Test
    void openCodeServiceServesRuntimeSettingsThroughTheModelOwnedPort() {
        OpenCodeSettingsService svc = new OpenCodeSettingsService(
                new MemOpenCodeRepo(new OpenCodeSettings(
                        "oc-secret-key", "e-key", "gpt-5.6-terra",
                        Instant.now(), Instant.now())),
                Mockito.mock(OpenCodeModelCatalog.class),
                Mockito.mock(OpenCodeZenTransport.class));
        assertThat(svc).isInstanceOf(OpenCodeRuntimeSettingsPort.class);
        OpenCodeRuntimeSettingsPort port = svc;

        RuntimeOpenCodeSettings settings = port.requireRuntimeSettings();

        assertThat(settings.apiKey()).isEqualTo("oc-secret-key");
        assertThat(settings.selectedModel()).isEqualTo("gpt-5.6-terra");
        assertThat(settings.credentialSource()).isEqualTo("database:opencode_settings");
        assertThat(settings.toString()).doesNotContain("oc-secret-key");
    }

    @Test
    void openCodePortFailsClosedWhenUnconfigured() {
        OpenCodeSettingsService svc = new OpenCodeSettingsService(
                new MemOpenCodeRepo(null),
                Mockito.mock(OpenCodeModelCatalog.class),
                Mockito.mock(OpenCodeZenTransport.class));
        OpenCodeRuntimeSettingsPort port = svc;

        assertThatThrownBy(port::requireRuntimeSettings)
                .isInstanceOf(OpenCodeModelException.class);
    }

    // ---- helpers --------------------------------------------------------

    private static ProtocolAdapterRegistry registry() {
        return new ProtocolAdapterRegistry(java.util.List.of(
                new ChatCompletionsProtocolAdapter(),
                new ResponsesProtocolAdapter(),
                new AnthropicMessagesProtocolAdapter()));
    }

    private static CompatibilityProbeService noopProbe() {
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

    private static OpenRouterSettingsService openRouterService(OpenRouterSettingsRepository repo) {
        return new OpenRouterSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
    }

    private static CustomProviderSettingsService customService(CustomProviderSettingsRepository repo) {
        return new CustomProviderSettingsService(repo, new ObjectMapper(), registry(), noopProbe());
    }

    private static CustomProviderSettings customSettings(String format, String baseUrl, String key,
            String model, long configRevision, Long validatedRevision, Instant now) {
        return new CustomProviderSettings(format, baseUrl, key,
                key == null || key.length() <= 4 ? null : key.substring(key.length() - 4),
                model, "MANUAL", null, configRevision, validatedRevision, now, now,
                validatedRevision == null ? null : now);
    }

    private static final class MemOpenRouterRepo implements OpenRouterSettingsRepository {
        OpenRouterSettings stored;

        @Override
        public Optional<OpenRouterSettings> find() {
            return Optional.ofNullable(stored);
        }

        @Override
        public void upsert(OpenRouterSettings settings) {
            stored = settings;
        }

        @Override
        public void markValidated(long revision) {
            stored = new OpenRouterSettings(stored.apiKey(), stored.maskedSuffix(),
                    stored.selectedModel(), stored.configRevision(), revision,
                    stored.createdAt(), stored.updatedAt(), Instant.now());
        }
    }

    private static final class MemCustomRepo implements CustomProviderSettingsRepository {
        CustomProviderSettings stored;

        @Override
        public Optional<CustomProviderSettings> find() {
            return Optional.ofNullable(stored);
        }

        @Override
        public void upsert(CustomProviderSettings settings) {
            stored = settings;
        }

        @Override
        public void markValidated(long revision) {
            stored = new CustomProviderSettings(stored.apiFormat(), stored.baseUrl(),
                    stored.apiKey(), stored.maskedSuffix(), stored.selectedModel(),
                    stored.modelSource(), stored.displayName(), stored.configRevision(),
                    revision, stored.createdAt(), stored.updatedAt(), Instant.now());
        }
    }

    private static final class MemOpenCodeRepo implements OpenCodeSettingsRepository {
        private final OpenCodeSettings stored;

        MemOpenCodeRepo(OpenCodeSettings stored) {
            this.stored = stored;
        }

        @Override
        public Optional<OpenCodeSettings> find() {
            return Optional.ofNullable(stored);
        }

        @Override
        public void upsert(OpenCodeSettings settings) {
            throw new UnsupportedOperationException("read-only test repository");
        }
    }
}
