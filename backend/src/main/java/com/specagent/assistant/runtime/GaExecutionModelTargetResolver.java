package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;
import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.config.GaNativeModelQualification;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.OpenCodeModelCatalog;
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
 * 它负责解析并冻结:提供商、模型 id、设置修订号、已解析凭据,以及<b>该模型是否具备
 * GA 原生工具调用资格</b>。
 *
 * <p>三层职责刻意分开:
 * <ol>
 *   <li>{@link #resolveActive()} —— 准备阶段按<b>当前激活</b>提供商解析目标,产出控件
 *       可终态化的类型化结果(未配置 / 未取得资格),不再一律抛
 *       {@code GA_MODEL_NOT_CONFIGURED};</li>
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

    /** 解析结果:要么是可执行目标,要么是应被如实终态化的类型化不可用原因。 */
    public sealed interface Resolution permits Ready, Unavailable {
    }

    public record Ready(GaModelTarget target) implements Resolution {
    }

    public record Unavailable(String errorCode, String reason) implements Resolution {
    }

    private final ModelProviderSettingsService providers;
    private final OpenCodeSettingsRepository openCodeRepository;
    private final OpenCodeSettingsService openCodeSettings;
    private final OpenCodeModelCatalog openCodeModelProtocols;
    private final KiloSettingsService kiloSettings;
    private final GaNativeModelQualification qualification;
    private final GaBrainSettings hostSettings;

    public GaExecutionModelTargetResolver(ModelProviderSettingsService providers,
                                          OpenCodeSettingsRepository openCodeRepository,
                                          OpenCodeSettingsService openCodeSettings,
                                          OpenCodeModelCatalog openCodeModelProtocols,
                                          KiloSettingsService kiloSettings,
                                          GaNativeModelQualification qualification,
                                          GaBrainSettings hostSettings) {
        this.providers = providers;
        this.openCodeRepository = openCodeRepository;
        this.openCodeSettings = openCodeSettings;
        this.openCodeModelProtocols = openCodeModelProtocols;
        this.kiloSettings = kiloSettings;
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
        try {
            active = providers.activeProvider();
        } catch (RuntimeException ex) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "The active model provider could not be resolved");
        }
        return resolveProvider(active, true);
    }

    /**
     * 推理阶段:按已冻结绑定重新解析可执行目标。提供商与模型都与绑定比对,设置修订号
     * 漂移同样失败关闭——运行中用户改设置不会让同一个 Run 改用它没有冻结的目标。
     *
     * <p><b>刻意不在此重复施加资格门禁</b>:协议/工具调用资格是准备阶段的一次性判定,
     * 已经随绑定冻结(且绑定修订号与当前设置一致才可能走到这里)。若在每次模型调用上重
     * 判定,缓存元数据的中途失效会把一个已被合法接受的 Run 判死——那不是绑定漂移,而是
     * 重复门禁。这里只校验"该提供商的当前设置仍与冻结的模型/修订号一致"并解析凭据。
     */
    public GaModelTarget requireFrozen(String providerCode, String frozenModel, String frozenRevision) {
        ModelProvider provider;
        try {
            provider = ModelProvider.fromCode(providerCode);
        } catch (IllegalArgumentException unknownProvider) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        GaModelTarget target = switch (resolveProvider(provider, false)) {
            case Ready ready -> ready.target();
            // "该提供商尚未取得 GA 原生工具调用资格"不再被 GA_MODEL_NOT_CONFIGURED 掩盖。
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

    private Resolution resolveProvider(ModelProvider provider, boolean applyQualificationGate) {
        return switch (provider) {
            case OPENCODE_ZEN -> resolveOpenCode(applyQualificationGate);
            case KILO -> resolveKilo(applyQualificationGate);
            // OpenRouter / Custom 尚未取得 GA 原生工具调用资格(没有已注册的原生传输)。
            case OPENROUTER, CUSTOM -> unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                    "The provider has no qualified native GA tool-calling transport");
        };
    }

    private Resolution resolveOpenCode(boolean applyQualificationGate) {
        OpenCodeSettings stored = openCodeRepository.find().orElse(null);
        if (stored == null || stored.selectedModel() == null || stored.selectedModel().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "OpenCode settings are not configured");
        }
        String model = stored.selectedModel();
        // 协议门禁只读已缓存的可信元数据(绝不触发网络刷新),且只在准备阶段施加。
        if (applyQualificationGate && !openCodeModelProtocols.verifiedChatExecutable(model)) {
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
                "opencode-settings:" + stored.updatedAt(), credential));
    }

    private Resolution resolveKilo(boolean applyQualificationGate) {
        var stored = kiloSettings.stored().orElse(null);
        if (stored == null || stored.selectedModel() == null || stored.selectedModel().isBlank()) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_NOT_CONFIGURED,
                    "Kilo Gateway settings are not configured");
        }
        String model = stored.selectedModel();
        // Kilo 全部是 Chat Completions,但原生工具调用资格必须逐模型经验证:绝不按
        // :free 后缀或"是个 Chat 模型"推断。未登记即失败关闭(仅准备阶段)。
        if (applyQualificationGate && !qualification.kiloQualified(model)) {
            return unavailable(GlobalAssistantErrorCode.GA_MODEL_PROTOCOL_UNSUPPORTED,
                    "The selected Kilo model is not qualified for native GA tool calling");
        }
        return new Ready(new GaModelTarget(ModelProvider.KILO.name(), model,
                "kilo-settings:" + stored.updatedAt(), stored.apiKey()));
    }

    private static Unavailable unavailable(String errorCode, String reason) {
        return new Unavailable(errorCode, reason);
    }
}
