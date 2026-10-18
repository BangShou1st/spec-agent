package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.config.GaNativeModelQualification;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.modelsettings.KiloSettings;
import com.specagent.modelsettings.KiloSettingsService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.modelsettings.OpenCodeSettingsService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 文件名:GaExecutionModelTargetResolverTest.java
 *
 * 测试目标:GA 模型目标解析的提供商无关性与失败关闭语义——四个提供商的解析结果、
 * "未配置"与"未取得资格"的类型化区分、以及冻结绑定绝不被运行中的提供商/模型/设置
 * 变更重新定向。
 */
class GaExecutionModelTargetResolverTest {

    private final ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
    private final OpenCodeSettingsRepository openCodeRepository = mock(OpenCodeSettingsRepository.class);
    private final OpenCodeSettingsService openCodeSettings = mock(OpenCodeSettingsService.class);
    private final OpenCodeModelCatalog openCodeModelProtocols = mock(OpenCodeModelCatalog.class);
    private final KiloSettingsService kiloSettings = mock(KiloSettingsService.class);
    private final GaNativeModelQualification qualification = mock(GaNativeModelQualification.class);
    private final GaBrainSettings host = mock(GaBrainSettings.class);

    private GaExecutionModelTargetResolver resolver() {
        return new GaExecutionModelTargetResolver(providers, openCodeRepository, openCodeSettings,
                openCodeModelProtocols, kiloSettings, qualification, host);
    }

    private void hostSecret(String secret) {
        when(host.getInternalSecret()).thenReturn(secret);
    }

    private static GaModelTarget ready(GaExecutionModelTargetResolver.Resolution resolution) {
        assertThat(resolution).isInstanceOf(GaExecutionModelTargetResolver.Ready.class);
        return ((GaExecutionModelTargetResolver.Ready) resolution).target();
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

    @Test
    void verifiedOpenCodeChatModelResolvesWithItsSettingsRevision() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:00:00Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        when(openCodeRepository.find()).thenReturn(Optional.of(openCode("gpt-5.6-terra", updated)));
        when(openCodeModelProtocols.verifiedChatExecutable("gpt-5.6-terra")).thenReturn(true);
        when(openCodeSettings.credentialForModelCached("gpt-5.6-terra", "zen-key")).thenReturn("public");

        GaModelTarget target = ready(resolver().resolveActive());

        assertThat(target.provider()).isEqualTo("OPENCODE_ZEN");
        assertThat(target.model()).isEqualTo("gpt-5.6-terra");
        assertThat(target.settingsRevision()).isEqualTo("opencode-settings:" + updated);
        assertThat(target.credential()).isEqualTo("public");
    }

    @Test
    void qualifiedKiloModelResolves() {
        hostSecret("secret");
        Instant updated = Instant.parse("2026-10-18T09:38:58Z");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "poolside/laguna-s-2.1:free", updated)));
        when(qualification.kiloQualified("poolside/laguna-s-2.1:free")).thenReturn(true);

        GaModelTarget target = ready(resolver().resolveActive());

        assertThat(target.provider()).isEqualTo("KILO");
        assertThat(target.model()).isEqualTo("poolside/laguna-s-2.1:free");
        assertThat(target.settingsRevision()).isEqualTo("kilo-settings:" + updated);
        // 免费模型匿名执行:没有凭据绝不等于失败。
        assertThat(target.hasCredential()).isFalse();
    }

    @Test
    void kiloModelWithoutNativeToolQualificationIsProtocolUnsupportedNotConfigured() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        when(kiloSettings.stored()).thenReturn(Optional.of(kilo(null, "vendor/model:free", Instant.now())));
        when(qualification.kiloQualified("vendor/model:free")).thenReturn(false);

        // :free 后缀绝不等于"支持原生工具调用"。
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED);
    }

    @Test
    void openRouterAndCustomHaveNoNativeGaTransport() {
        hostSecret("secret");
        when(providers.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        assertThat(unavailableCode(resolver().resolveActive()))
                .isEqualTo(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED);

        when(providers.activeProvider()).thenReturn(ModelProvider.CUSTOM);
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
    void frozenBindingDoesNotRepeatThePreparationTimeQualificationGate() {
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
