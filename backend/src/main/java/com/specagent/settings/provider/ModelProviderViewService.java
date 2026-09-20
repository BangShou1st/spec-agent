package com.specagent.settings.provider;

import com.specagent.model.provider.ModelProvider;
import com.specagent.settings.custom.CustomProviderSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Projects the provider registry (preset cards + user rows) into the view
 * shapes the settings page renders.
 *
 * <p>The preset knowledge — which affordances a card may show, which base URL
 * and display name a preset starts from — lives here next to the other
 * provider services, so the API layer only consumes finished views and never
 * touches {@code model.provider} directly.
 */
@Service
public class ModelProviderViewService {

    private final ModelProvidersService providers;
    private final ModelProviderSettingsService active;
    private final OpenCodeSettingsService openCode;
    private final OpenRouterSettingsService openRouter;
    private final CustomProviderSettingsService custom;

    public ModelProviderViewService(ModelProvidersService providers, ModelProviderSettingsService active,
                                    OpenCodeSettingsService openCode, OpenRouterSettingsService openRouter,
                                    CustomProviderSettingsService custom) {
        this.providers = providers;
        this.active = active;
        this.openCode = openCode;
        this.openRouter = openRouter;
        this.custom = custom;
    }

    /** Edit affordances the card is allowed to render for this preset. */
    public record Capabilities(boolean userNamed, boolean selectableFormat, boolean editableBaseUrl,
                               boolean requiresApiKey, boolean builtInCatalog) {
    }

    public record ProviderView(String id, String preset, String displayName, String apiFormat, String baseUrl,
                               String endpointPreview, boolean hasKey, String maskedKey, String selectedModel,
                               boolean manualModel, boolean configured, long configRevision, boolean validated,
                               boolean active, Capabilities capabilities) {
    }

    public record ProviderListView(List<ProviderView> providers, String activeProvider, String activeProviderId) {
    }

    /** One uniform list over presets and user-defined rows. */
    public ProviderListView listView() {
        List<ProviderView> views = new ArrayList<>();
        views.add(presetView(ModelProvider.OPENCODE_ZEN));
        views.add(presetView(ModelProvider.OPENROUTER));
        for (ModelProviderRecord record : providers.list()) {
            views.add(rowView(record));
        }
        return new ProviderListView(views, active.activeProviderCode(),
                active.activeProviderId() == null ? null : active.activeProviderId().toString());
    }

    public ProviderView rowView(ModelProviderRecord record) {
        return new ProviderView(
                record.id().toString(),
                record.preset().name(),
                record.effectiveDisplayName(),
                record.apiFormat(),
                record.baseUrl(),
                custom.previewEndpoint(record.apiFormat(), record.baseUrl()),
                record.hasKey(),
                mask(record.maskedSuffix()),
                record.selectedModel(),
                record.manualModel(),
                record.configured(),
                record.configRevision(),
                record.validated(),
                active.isActiveId(record.id()),
                capabilitiesOf(record.preset()));
    }

    private ProviderView presetView(ModelProvider preset) {
        if (preset == ModelProvider.OPENCODE_ZEN) {
            var status = openCode.status();
            return presetStatusView(preset, status.configured(), status.maskedKey(),
                    status.selectedModel(), 0L, false);
        }
        boolean isActive = active.isActiveCode(preset.name());
        var status = openRouter.status(isActive);
        return presetStatusView(preset, status.configured(), status.maskedKey(),
                status.selectedModel(), status.configRevision(), status.validated());
    }

    private ProviderView presetStatusView(ModelProvider preset, boolean configured, String maskedKey,
                                          String selectedModel, long configRevision, boolean validated) {
        boolean isActive = active.isActiveCode(preset.name()) && active.activeProviderId() == null;
        return new ProviderView(
                preset.name(), preset.name(), preset.defaultDisplayName(), "CHAT_COMPLETIONS",
                preset.defaultBaseUrl(), null, maskedKey != null, maskedKey, selectedModel, false,
                configured, configRevision, validated, isActive, capabilitiesOf(preset));
    }

    private static Capabilities capabilitiesOf(ModelProvider preset) {
        return new Capabilities(preset.userNamed(), preset.selectableFormat(), preset.editableBaseUrl(),
                preset.requiresApiKey(), preset.builtInCatalog());
    }

    private static String mask(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        return "••••" + suffix;
    }
}
