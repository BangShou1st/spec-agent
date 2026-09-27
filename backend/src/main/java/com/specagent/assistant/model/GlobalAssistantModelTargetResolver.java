package com.specagent.assistant.model;

import com.specagent.model.contract.ModelProvider;
import com.specagent.modelsettings.OpenRouterSettingsService;
import com.specagent.modelsettings.OpenCodeSettingsService;
import com.specagent.modelsettings.ModelProviderRecord;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.modelsettings.ModelProvidersService;
import org.springframework.stereotype.Component;

/**
 * 文件名:GlobalAssistantModelTargetResolver.java
 *
 * 用途:解析当前激活的模型目标,产出脱敏后的展示信息
 * ({@code providerLabel} + {@code modelId}),用于消息的模型归属展示。
 * 只读且 fail-safe:任何解析失败都返回 {@code null} 字段——归属信息只是
 * 装饰性的,绝不能因此打断一次运行。
 *
 * 各预设并不是 {@code model_providers} 表里的一行(V37 中 OpenCode Zen
 * 与 OpenRouter 走各自专用的设置表),所以每种供应商都从路由网关实际读取的
 * 存储里取模型:OpenCode 运行时设置、OpenRouter 状态,只有 CUSTOM
 * 才查供应商注册表。
 */
@Component
public class GlobalAssistantModelTargetResolver {

    /** 供应商展示名 + 所选模型 ID,两者都可能为 null。 */
    public record ModelTarget(String providerLabel, String modelId) {
    }

    private final ModelProviderSettingsService providerSettings;
    private final ModelProvidersService providers;
    private final OpenCodeSettingsService openCodeSettings;
    private final OpenRouterSettingsService openRouterSettings;

    public GlobalAssistantModelTargetResolver(ModelProviderSettingsService providerSettings,
            ModelProvidersService providers,
            OpenCodeSettingsService openCodeSettings,
            OpenRouterSettingsService openRouterSettings) {
        this.providerSettings = providerSettings;
        this.providers = providers;
        this.openCodeSettings = openCodeSettings;
        this.openRouterSettings = openRouterSettings;
    }

    /** 调用时解析当前激活目标;绝不抛异常。 */
    public ModelTarget resolveActive() {
        try {
            ModelProvider active = providerSettings.activeProvider();
            return switch (active) {
                case OPENCODE_ZEN -> new ModelTarget(active.defaultDisplayName(),
                        trimToNull(openCodeSettings.requireRuntimeSettings().selectedModel()));
                case OPENROUTER -> new ModelTarget(active.defaultDisplayName(),
                        trimToNull(openRouterSettings.status(true).selectedModel()));
                case CUSTOM -> customTarget(active);
            };
        } catch (RuntimeException ex) {
            return new ModelTarget(null, null);
        }
    }

    private ModelTarget customTarget(ModelProvider active) {
        ModelProviderRecord row = findActiveCustomRow();
        if (row == null) {
            return new ModelTarget(active.defaultDisplayName(), null);
        }
        return new ModelTarget(row.effectiveDisplayName(), trimToNull(row.selectedModel()));
    }

    private ModelProviderRecord findActiveCustomRow() {
        java.util.UUID activeId = providerSettings.activeProviderId();
        if (activeId != null) {
            for (ModelProviderRecord row : providers.list()) {
                if (activeId.equals(row.id())) {
                    return row;
                }
            }
        }
        // 预设式的仅代码激活:第一个 CUSTOM 行就是路由网关实际委托的对象。
        // 未配置时返回 null,而不是抛异常。
        try {
            return providers.findFirstByPreset(ModelProvider.CUSTOM);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
