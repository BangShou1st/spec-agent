package com.specagent.globalassistant.model;

import com.specagent.model.provider.ModelProvider;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.provider.ModelProviderRecord;
import com.specagent.settings.provider.ModelProviderSettingsService;
import com.specagent.settings.provider.ModelProvidersService;
import org.springframework.stereotype.Component;

/**
 * Resolves the currently active model target as sanitized display
 * accounting ({@code providerLabel} + {@code modelId}) for message
 * attribution. Read-only and fail-safe: any resolution failure returns
 * {@code null} fields — attribution is cosmetic and must never break a run.
 *
 * <p>The presets are NOT rows in {@code model_providers} (V37 keeps OpenCode
 * Zen and OpenRouter on their dedicated settings tables), so each provider
 * kind reads its model from the store the routing gateway actually uses:
 * OpenCode runtime settings, the OpenRouter status, and only CUSTOM from the
 * provider registry.
 */
@Component
public class GlobalAssistantModelTargetResolver {

    /** Provider display label + selected model id, both nullable. */
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

    /** Resolves the active target at call time; never throws. */
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
        // Preset-style code-only activation: the first custom row is the one
        // the routing gateway delegates to. Unconfigured -> null, not a throw.
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
