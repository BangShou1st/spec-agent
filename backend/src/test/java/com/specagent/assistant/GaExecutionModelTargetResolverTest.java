package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.config.GaNativeModelQualification;
import com.specagent.assistant.runtime.GaExecutionMode;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.modelsettings.CustomProviderSettings;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.KiloSettings;
import com.specagent.modelsettings.KiloSettingsService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.modelsettings.OpenCodeSettingsService;
import com.specagent.modelsettings.OpenRouterSettings;
import com.specagent.modelsettings.OpenRouterSettingsService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 文件名:GaExecutionModelTargetResolverTest.java
 *
 * 测试目标:GA 模型目标解析的提供商无关性、能力模式判定与失败关闭语义——
 * "用户选什么模型就用什么模型";原生工具资格只决定 {@code NATIVE_TOOLS} 与
 * {@code TEXT_CHAT} 之分,绝不阻止普通聊天;四个提供商的解析结果、协议不支持的
 * 类型化区分、以及冻结绑定绝不被运行中的提供商/模型/设置变更重新定向。
 */
class GaExecutionModelTargetResolverTest {

    private final ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
    private final OpenCodeSettingsRepository openCodeRepository = mock(OpenCodeSettingsRepository.class);
    private final OpenCodeSettingsService openCodeSettings = mock(OpenCodeSettingsService.class);
    private final OpenCodeModelCatalog openCodeModelProtocols = mock(OpenCodeModelCatalog.class);
    private final KiloSettingsService kiloSettings = mock(KiloSettingsService.class);
    private final OpenRouterSettingsService openRouterSettings = mock(OpenRouterSettingsService.class);
    private final CustomProviderSettingsService customSettings = mock(CustomProviderSettingsService.class);
    private final GaNativeModelQualification qualification = mock(GaNativeModelQualification.class);
    private final GaBrainSettings host = mock(GaBrainSettings.class);

    private GaExecutionModelTargetResolver resolver() {
        return new GaExecutionModelTargetResolver(providers, openCodeRepository, openCodeSettings,
                openCodeModelProtocols, kiloSettings, openRouterSettings, customSettings, qualification, host);
    }

    private void hostSecret(String secret) {
        when(host.getInternalSecret()).thenReturn(secret);
    }

    private static GaExecutionModelTargetResolver.Ready ready(GaExecutionModelTargetResolver.Resolution resolution) {
        assertThat(resolution).isInstanceOf(GaExecutionModelTargetResolver.Ready.class);
        return (GaExecutionModelTargetResolver.Ready) resolution;
    }

    private static String unavailableCode(GaExecutionModelTargetResolver.Resolution resolution) {
        assertThat(resolution).isInstanceOf(GaExecutionModelTargetResolver.Unavailable.class);
        return ((GaExecutionModelTargetResolver.Unavailable) resolution).errorCode();
    }

    private static OpenCodeSettings openCode(String model, Instant updatedAt) {
        return new OpenCodeSettings("zen-key", "1234", model, updatedAt, updatedAt);
    }

    private static KiloSettings kilo(String key, String model, Instant updatedAt) {
        return new KiloSettings(key, key == null ? null : "1234", model, updatedAt, updatedAt);
    }

    private static OpenRouterSettings openRouter(String model, long revision) {
        Instant now = Instant.now();
        return new OpenRouterSettings("or-key", "1234", model, revision, revision, now, now, now);
    }

    private static CustomProviderSettings custom(String format, String model, long revision) {
        Instant now = Instant.now();
        return new CustomProviderSettings(format, "https://custom.example/v1", "custom-key", "1234",
                model, "DISCOVERED", "Custom", revision, revision, now, now, now);
    }

    @Test
    void verifiedOpenCodeChatModelResolvesWithItsSettingsRevision() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:00:00Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        when(openCodeRepository.find()).thenReturn(Optional.of(openCode("gpt-5.6-terra", updated)));
        when(openCodeModelProtocols.verifiedChatExecutable("gpt-5.6-terra")).thenReturn(true);
        when(openCodeSettings.credentialForModelCached("gpt-5.6-terra", "zen-key")).thenReturn("public");

        var resolution = ready(resolver().resolveActive());

        assertThat(resolution.target().provider()).isEqualTo("OPENCODE_ZEN");
        assertThat(resolution.target().model()).isEqualTo("gpt-5.6-terra");
        assertThat(resolution.target().settingsRevision()).isEqualTo("opencode-settings:" + updated);
        assertThat(resolution.target().credential()).isEqualTo("public");
        assertThat(resolution.mode()).isEqualTo(GaExecutionMode.NATIVE_TOOLS);
    }

    @Test
    void qualifiedKiloModelResolvesWithNativeToolCapability() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "poolside/laguna-s-2.1:free", updated)));
        // 唯一边界判定:免费档恒匿名,即使曾保存过付费密钥。
        when(kiloSettings.credentialForModel("poolside/laguna-s-2.1:free", null)).thenReturn(null);
        when(qualification.kiloQualified("poolside/laguna-s-2.1:free")).thenReturn(true);

        var resolution = ready(resolver().resolveActive());

        assertThat(resolution.target().provider()).isEqualTo("KILO");
        assertThat(resolution.target().model()).isEqualTo("poolside/laguna-s-2.1:free");
        assertThat(resolution.target().settingsRevision()).isEqualTo("kilo-settings:" + updated);
        // 免费模型匿名执行:没有凭据绝不等于失败。
        assertThat(resolution.target().hasCredential()).isFalse();
        assertThat(resolution.mode()).isEqualTo(GaExecutionMode.NATIVE_TOOLS);
    }

    @Test
    void freeKiloModelStaysAnonymousEvenWithAStoredPaidKey() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo("paid-key", "poolside/laguna-s-2.1:free", updated)));
        // 边界把付费密钥解析为匿名:GA 目标绝不携带它。
        when(kiloSettings.credentialForModel("poolside/laguna-s-2.1:free", "paid-key")).thenReturn(null);
        when(qualification.kiloQualified("poolside/laguna-s-2.1:free")).thenReturn(true);

        var resolution = ready(resolver().resolveActive());

        assertThat(resolution.target().hasCredential()).isFalse();
        assertThat(resolution.target().credential()).isNull();
    }

    @Test
    void paidKiloModelResolvesWithItsResolvedCredential() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo("paid-key", "cohere/north-mini-code", updated)));
        when(kiloSettings.credentialForModel("cohere/north-mini-code", "paid-key")).thenReturn("paid-key");
        when(qualification.kiloQualified("cohere/north-mini-code")).thenReturn(true);

        var resolution = ready(resolver().resolveActive());

        assertThat(resolution.target().credential()).isEqualTo("paid-key");
    }

    @Test
    void paidKiloModelWithoutACredentialIsNotConfigured() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "cohere/north-mini-code", Instant.now())));
        when(kiloSettings.credentialForModel("cohere/north-mini-code", null))
                .thenThrow(ModelProviderException.notConfigured("kilo", "Paid Kilo models require an API key"));

        // 付费模型缺少密钥时在准备阶段失败关闭,绝不把空凭据带进传输层。
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED);
    }

    @Test
    void kiloModelWithoutNativeToolQualificationStillChatsAsText() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "vendor/model:free", Instant.now())));
        when(qualification.kiloQualified("vendor/model:free")).thenReturn(false);

        // :free 后缀绝不等于"支持原生工具调用"——但它也绝不阻止普通聊天。
        var resolution = ready(resolver().resolveActive());
        assertThat(resolution.target().model()).isEqualTo("vendor/model:free");
        assertThat(resolution.mode()).isEqualTo(GaExecutionMode.TEXT_CHAT);
    }

    @Test
    void configuredOpenRouterResolvesAsTextChat() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        when(openRouterSettings.requireStoredOrNull()).thenReturn(openRouter("openrouter/auto", 3));
        when(openRouterSettings.activatable()).thenReturn(true);

        var resolution = ready(resolver().resolveActive());
        assertThat(resolution.target().provider()).isEqualTo("OPENROUTER");
        assertThat(resolution.target().model()).isEqualTo("openrouter/auto");
        assertThat(resolution.target().settingsRevision()).isEqualTo("openrouter-settings:3");
        // 原生工具传输尚未验证,但普通聊天仍然可用。
        assertThat(resolution.mode()).isEqualTo(GaExecutionMode.TEXT_CHAT);
    }

    @Test
    void unvalidatedOpenRouterIsNotConfigured() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        when(openRouterSettings.requireStoredOrNull()).thenReturn(openRouter("openrouter/auto", 3));
        when(openRouterSettings.activatable()).thenReturn(false);

        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED);
    }

    @Test
    void customChatCompletionsResolvesAsTextChat() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        when(customSettings.requireStoredOrNull()).thenReturn(custom("CHAT_COMPLETIONS", "local-llama", 2));
        when(customSettings.activatable()).thenReturn(true);

        var resolution = ready(resolver().resolveActive());
        assertThat(resolution.target().provider()).isEqualTo("CUSTOM");
        assertThat(resolution.target().model()).isEqualTo("local-llama");
        assertThat(resolution.target().settingsRevision()).isEqualTo("custom-settings:2");
        assertThat(resolution.mode()).isEqualTo(GaExecutionMode.TEXT_CHAT);
    }

    @Test
    void customNonChatCompletionsFormatIsProtocolUnsupported() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        when(customSettings.requireStoredOrNull()).thenReturn(custom("RESPONSES", "some-model", 1));

        // Responses / Anthropic 绝不能被默认为受支持的 Chat Completions。
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED);
    }

    @Test
    void missingSettingsAndMissingHostSecretAreNotConfigured() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.empty());
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED);

        hostSecret("");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED);
    }

    @Test
    void unverifiedOpenCodeModelIsProtocolUnsupported() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        when(openCodeRepository.find()).thenReturn(Optional.of(openCode("responses-only", Instant.now())));
        when(openCodeModelProtocols.verifiedChatExecutable("responses-only")).thenReturn(false);

        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED);
    }

    @Test
    void frozenKiloBindingSurvivesTheActiveProviderSwitchingToZen() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        // 用户在执行过程中把激活提供商切回 OpenCode Zen。
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "poolside/laguna-s-2.1:free", updated)));
        when(qualification.kiloQualified("poolside/laguna-s-2.1:free")).thenReturn(true);

        // 本 Run 的冻结绑定仍是 KILO:绝不被重新定向到 Zen。
        GaModelTarget target = resolver().requireFrozen("KILO", "poolside/laguna-s-2.1:free",
                "kilo-settings:" + updated);

        assertThat(target.provider()).isEqualTo("KILO");
        assertThat(target.model()).isEqualTo("poolside/laguna-s-2.1:free");
    }

    @Test
    void frozenOpenRouterBindingIsReResolvedFromTheFrozenProvider() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(openRouterSettings.requireStoredOrNull()).thenReturn(openRouter("openrouter/auto", 3));

        GaModelTarget target = resolver().requireFrozen("OPENROUTER", "openrouter/auto",
                "openrouter-settings:3");

        assertThat(target.provider()).isEqualTo("OPENROUTER");
        assertThat(target.model()).isEqualTo("openrouter/auto");
    }

    @Test
    void frozenBindingRejectsModelAndRevisionDrift() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "poolside/laguna-s-2.1:free", updated)));
        when(qualification.kiloQualified("poolside/laguna-s-2.1:free")).thenReturn(true);

        // 同一提供商切换模型。
        assertThatThrownBy(() -> resolver().requireFrozen("KILO", "cohere/north-mini-code:free",
                "kilo-settings:" + updated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GA_MODEL_BINDING_MISMATCH");
        // 设置修订号变化(例如换 Key)同样失败关闭。
        assertThatThrownBy(() -> resolver().requireFrozen("KILO", "poolside/laguna-s-2.1:free",
                "kilo-settings:2026-10-18T10:00:00Z"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GA_MODEL_BINDING_MISMATCH");
    }

    @Test
    void frozenBindingRejectsAnUnknownProviderCode() {
        hostSecret("secret");

        assertThatThrownBy(() -> resolver().requireFrozen("NOT_A_PROVIDER", "m", "r"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GA_MODEL_BINDING_MISMATCH");
    }

    @Test
    void frozenBindingDoesNotRepeatThePreparationTimeProtocolGate() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:00:00Z");
        when(openCodeRepository.find()).thenReturn(Optional.of(openCode("test-model", updated)));
        // 元数据不可用/未验证:准备阶段会失败关闭,但推理阶段不得重复施加该门禁。
        when(openCodeModelProtocols.verifiedChatExecutable("test-model")).thenReturn(false);

        GaModelTarget target = resolver().requireFrozen("OPENCODE_ZEN", "test-model",
                "opencode-settings:" + updated);

        assertThat(target.model()).isEqualTo("test-model");
    }
}
