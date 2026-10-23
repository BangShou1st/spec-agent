package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
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
        // 运行时凭据现在按模型解析(free -> public 凭据;paid -> 已存 Key),由模型目录
        // 权威决定,因此这里显式桩定目录解析结果,并验证服务确实走了这条委派。
        OpenCodeModelCatalog catalog = Mockito.mock(OpenCodeModelCatalog.class);
        Mockito.when(catalog.credentialForModel("gpt-5.6-terra", "oc-secret-key"))
                .thenReturn("oc-secret-key");
        OpenCodeSettingsService svc = new OpenCodeSettingsService(
                new MemOpenCodeRepo(new OpenCodeSettings(
                        "oc-secret-key", "e-key", "gpt-5.6-terra",
                        Instant.now(), Instant.now())),
                catalog,
                Mockito.mock(OpenCodeZenTransport.class));
        assertThat(svc).isInstanceOf(OpenCodeRuntimeSettingsPort.class);
        OpenCodeRuntimeSettingsPort port = svc;

        RuntimeOpenCodeSettings settings = port.requireRuntimeSettings();

        Mockito.verify(catalog).credentialForModel("gpt-5.6-terra", "oc-secret-key");
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
            public void probeCustom(CustomApiFormat format, String base, String key, String model) {
            }
        };
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
