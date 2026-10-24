package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;
import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.config.GaNativeModelQualification;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.KiloSettingsService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.modelsettings.OpenCodeSettingsService;
import org.springframework.stereotype.Service;

/**
 * 文件名:GaExecutionModelTargetResolver.java
 *
 * 用途:GA 执行的提供商无关模型目标解析,也是"GA 是否支持某提供商"的唯一判定点。
 * 它负责解析并冻结:提供商、模型 id、设置修订号、已解析凭据,以及<b>该 Run 的执行
 * 能力模式</b>({@link GaExecutionMode})。
 *
 * <p><b>产品规则:用户选什么模型,Project Agent 和 GA 就用什么模型。</b>GA 不再维护
 * 第二份模型选择名单——原生工具调用资格只决定能否启用 {@link GaExecutionMode#NATIVE_TOOLS},
 * 绝不决定能否普通聊天。任何已合法配置、支持 Chat Completions 的选中模型都能
 * {@link GaExecutionMode#TEXT_CHAT}。
 *
 * <p>三层职责刻意分开:
 * <ol>
 *   <li>{@link #resolveActive()} —— 准备阶段按<b>当前激活</b>提供商解析目标与能力模式,
 *       产出控件可终态化的类型化结果(未配置 / 协议不支持 / 可用);</li>
 *   <li>{@link #requireFrozen(String, String, String)} —— 推理阶段按<b>已冻结绑定</b>
 *       重新解析:运行中切换提供商/模型不会改变同一个 Run 的目标,但设置修订号一旦
 *       漂移即失败关闭({@code GA_MODEL_BINDING_MISMATCH});</li>
 *   <li>{@link #warmActiveProvider()} —— 在 Run 锁与事务之外预热有网络请求的资格
 *       信息(仅 OpenCode Zen 需要)。</li>
 * </ol>
 *
 * <p>凭据只在这一层解析,绝不写入 GA 执行表、诊断日志或行线契约。
 */
@Service
public class GaExecutionModelTargetResolver {

    /** 解析结果:要么是可执行目标(含已冻结的能力模式),要么是应被如实终态化的类型化不可用原因。 */
    public sealed interface Resolution permits Ready, Unavailable {
    }

    public record Ready(GaModelTarget target, GaExecutionMode mode) implements Resolution {
    }

    public record Unavailable(String errorCode, String reason) implements Resolution {
    }

    private final ModelProviderSettingsService providers;
    private final OpenCodeSettingsRepository openCodeRepository;
    private final OpenCodeSettingsService openCodeSettings;
    private final OpenCodeModelCatalog openCodeModelProtocols;
    private final KiloSettingsService kiloSettings;
    private final CustomProviderSettingsService customSettings;
    private final GaNativeModelQualification qualification;
    private final GaBrainSettings hostSettings;

    public GaExecutionModelTargetResolver(ModelProviderSettingsService providers,
                                          OpenCodeSettingsRepository openCodeRepository,
                                          OpenCodeSettingsService openCodeSettings,
                                          OpenCodeModelCatalog openCodeModelProtocols,
                                          KiloSettingsService kiloSettings,

                                          CustomProviderSettingsService customSettings,
                                          GaNativeModelQualification qualification,
                                          GaBrainSettings hostSettings) {
        this.providers = providers;
        this.openCodeRepository = openCodeRepository;
        this.openCodeSettings = openCodeSettings;
        this.openCodeModelProtocols = openCodeModelProtocols;
        this.kiloSettings = kiloSettings;
        this.customSettings = customSettings;
        this.qualification = qualification;
        this.hostSettings = hostSettings;
    }

    /** 准备阶段:按当前激活提供商解析。 */
    public Resolution resolveActive() {
        if (hostSettings.getInternalSecret() == null || hostSettings.getInternalSecret().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "GA host internal secret is not configured");
        }
        ModelProvider active;
        java.util.UUID selectedId;
        try {
            var selected = providers.selection();
            active = selected.provider();
            selectedId = selected.providerId();
        } catch (RuntimeException ex) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "The active model provider could not be resolved");
        }
        return active == ModelProvider.CUSTOM ? resolveCustom(true,selectedId) : resolveProvider(active, true);
    }

    /**
     * 推理阶段:按已冻结绑定重新解析可执行目标。提供商与模型都与绑定比对,设置修订号
     * 漂移同样失败关闭——运行中用户改设置不会让同一个 Run 改用它没有冻结的目标。
     *
     * <p><b>刻意不在此重复施加协议/配置门禁</b>:能力模式是准备阶段的一次性判定,
     * 已经随绑定冻结。若在每次模型调用上重判定,缓存元数据或验证状态的中途失效会把一个
     * 已被合法接受的 Run 判死——那不是绑定漂移,而是重复门禁。这里只校验"该提供商的当前
     * 设置仍与冻结的模型/修订号一致"并解析凭据。
     */
    public GaModelTarget requireFrozen(String providerCode, String frozenModel, String frozenRevision) {
        if ("CUSTOM".equals(providerCode) && frozenRevision != null
                && frozenRevision.startsWith("custom-provider:")) {
            var settings = customSettings.requireRuntimeSettings(frozenRevision);
            if (!settings.selectedModel().equals(frozenModel)) {
                throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
            }
            return new GaModelTarget(providerCode, frozenModel, settings.bindingRevision(), settings.apiKey());
        }
        ModelProvider provider;
        try {
            provider = ModelProvider.fromCode(providerCode);
        } catch (IllegalArgumentException unknownProvider) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        GaModelTarget target = switch (resolveProvider(provider, false)) {
            case Ready ready -> ready.target();
            // "该提供商尚未配置"不再被静默放过。
            case Unavailable notExecutable -> throw new IllegalStateException(notExecutable.errorCode());
        };
        if (!target.model().equals(frozenModel) || !target.settingsRevision().equals(frozenRevision)) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        return target;
    }

    /**
     * 在 Run 锁与事务之外预热当前提供商的有网络请求的资格信息。尽力而为、绝不抛出:
     * 权威判定在 {@link #resolveActive()} 中失败关闭。
     */
    public void warmActiveProvider() {
        try {
            if (providers.activeProvider() == ModelProvider.OPENCODE_ZEN) {
                openCodeModelProtocols.warmMetadata();
            }
        } catch (RuntimeException ignored) {
            // 预热失败绝不放行,也不改变控制流;准备阶段会按缓存事实失败关闭。
        }
    }

    private Resolution resolveProvider(ModelProvider provider, boolean applyPreparationGate) {
        return switch (provider) {
            case OPENCODE_ZEN -> resolveOpenCode(applyPreparationGate);
            case KILO -> resolveKilo(applyPreparationGate);

            case CUSTOM -> resolveCustom(applyPreparationGate);
        };
    }

    /**
     * OpenCode Zen 保留既有受支持的协议与原生工具传输:只有经可信元数据确认
     * Chat Completions 的模型才进入执行信封,并且它的 GA 能力模式恒为
     * {@link GaExecutionMode#NATIVE_TOOLS}。
     */
    private Resolution resolveOpenCode(boolean applyPreparationGate) {
        OpenCodeSettings stored = openCodeRepository.find().orElse(null);
        if (stored == null || stored.selectedModel() == null || stored.selectedModel().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "OpenCode settings are not configured");
        }
        String model = stored.selectedModel();
        // 协议门禁只读已缓存的可信元数据(绝不触发网络刷新),且只在准备阶段施加。
        if (applyPreparationGate && !openCodeModelProtocols.verifiedChatExecutable(model)) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                    "The selected OpenCode model is not a metadata-verified Chat Completions model");
        }
        String credential;
        try {
            credential = openCodeSettings.credentialForModelCached(model, stored.apiKey());
        } catch (RuntimeException missingCredential) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "The OpenCode credential is not available for the selected model");
        }
        return new Ready(new GaModelTarget(ModelProvider.OPENCODE_ZEN.name(), model,
                "opencode-settings:" + stored.updatedAt(), credential), GaExecutionMode.NATIVE_TOOLS);
    }

    /**
     * Kilo 全部是 Chat Completions,因此任何已配置的选中模型都允许 GA
     * {@link GaExecutionMode#TEXT_CHAT}。原生工具调用资格只决定是否升级为
     * {@link GaExecutionMode#NATIVE_TOOLS}——绝不按 {@code :free} 后缀推断,也绝不
     * 因为未登记而拒绝普通聊天。
     *
     * <p>凭据取自 {@link KiloSettingsService#credentialForModel} 这一唯一边界:免费模型
     * 即使曾保存过付费密钥也一律匿名执行,付费模型缺少密钥则在此失败关闭(而不是把密钥
     * 或空凭据带进传输层)。
     */
    private Resolution resolveKilo(boolean applyPreparationGate) {
        var stored = kiloSettings.stored().orElse(null);
        if (stored == null || stored.selectedModel() == null || stored.selectedModel().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "Kilo Gateway settings are not configured");
        }
        String model = stored.selectedModel();
        String credential;
        try {
            credential = kiloSettings.credentialForModel(model, stored.apiKey());
        } catch (RuntimeException missingCredential) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "The Kilo credential is not available for the selected model");
        }
        // 资格登记只使用能力信息:未登记的模型仍然可以聊天,只是不启用原生工具。
        GaExecutionMode mode = qualification.kiloQualified(model)
                ? GaExecutionMode.NATIVE_TOOLS : GaExecutionMode.TEXT_CHAT;
        return new Ready(new GaModelTarget(ModelProvider.KILO.name(), model,
                "kilo-settings:" + stored.updatedAt(), credential), mode);
    }

    /**
     * Custom 支持 Chat Completions 与 Responses 两种已实现传输;Anthropic Messages 在
     * 准备阶段以 {@code GA_MODEL_PROTOCOL_UNSUPPORTED} 明确失败。原生工具模式完全由
     * 当前 UUID/修订号下的 NATIVE_TOOLS=VERIFIED 能力记录决定。
     */
    private Resolution resolveCustom(boolean applyPreparationGate) {
        return resolveCustom(applyPreparationGate,null);
    }

    private Resolution resolveCustom(boolean applyPreparationGate, java.util.UUID selectedId) {
        if (applyPreparationGate && selectedId != null) {
            try {
                var settings = customSettings.requireRuntimeSettings(selectedId);
                CustomApiFormat format = CustomApiFormat.fromCode(settings.apiFormat());
                if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
                    return unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                            "GA does not support the custom provider API format");
                }
                // Legacy URL/model registrations do not grant qualifications to
                // another UUID connection, credential or configuration revision.
                return new Ready(new GaModelTarget(ModelProvider.CUSTOM.name(), settings.selectedModel(),
                        settings.bindingRevision(), settings.apiKey()), settings.nativeToolsVerified()
                        ? GaExecutionMode.NATIVE_TOOLS : GaExecutionMode.TEXT_CHAT);
            } catch (RuntimeException ex) {
                return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                        "Custom connection is not validated for the current revision");
            }
        }
        var stored = customSettings.requireStoredOrNull();
        if (stored == null || stored.selectedModel() == null || stored.selectedModel().isBlank()
                || stored.baseUrl() == null || stored.baseUrl().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "Custom provider settings are not configured");
        }
        if (applyPreparationGate) {
            CustomApiFormat format;
            try {
                format = CustomApiFormat.fromCode(stored.apiFormat());
            } catch (IllegalArgumentException unknownFormat) {
                return unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                        "The custom provider API format is not supported by GA");
            }
            if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
                return unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                        "GA does not support the custom provider API format");
            }
            if (!customSettings.activatable()) {
                return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                        "Custom configuration is not validated for the current revision");
            }
        }
        // Legacy URL/model registrations qualified only the Chat Completions
        // transport; a legacy RESPONSES connection can chat but never carries
        // native tools (the Responses transport fails closed without UUID proof).
        boolean legacyNativeTools = CustomApiFormat.fromCode(stored.apiFormat()) == CustomApiFormat.CHAT_COMPLETIONS
                && qualification.customQualified(stored.baseUrl(), stored.selectedModel());
        return new Ready(new GaModelTarget(ModelProvider.CUSTOM.name(), stored.selectedModel(),
                "custom-settings:" + stored.configRevision(), stored.apiKey()),
                legacyNativeTools ? GaExecutionMode.NATIVE_TOOLS : GaExecutionMode.TEXT_CHAT);
    }

    private static Unavailable unavailable(String errorCode, String reason) {
        return new Unavailable(errorCode, reason);
    }
}
