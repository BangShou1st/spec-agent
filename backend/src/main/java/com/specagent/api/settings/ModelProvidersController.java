package com.specagent.api.settings;

import com.specagent.api.common.ApiErrorResponse;
import com.specagent.model.provider.ModelProvider;
import com.specagent.settings.custom.CustomProviderSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import com.specagent.settings.provider.ModelProviderRecord;
import com.specagent.settings.provider.ModelProvidersService;
import com.specagent.settings.provider.ModelProviderSettingsService;
import com.specagent.settings.provider.ProviderModelCatalogService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider registry: one uniform list over presets and user-defined rows.
 *
 * <p>This is the extension point. Adding a provider is a POST here, never a new
 * controller, table or enum value. Presets stay projected from their dedicated
 * services because their request shapes are special — OpenCode Zen issues
 * absolute direct calls with extra headers, and OpenRouter runs a qualification
 * pass — but to the settings page they are just two more cards in the same list.
 */
@RestController
@RequestMapping("/api/v1/settings/providers")
public class ModelProvidersController {

    private final ModelProvidersService providers;
    private final ModelProviderSettingsService active;
    private final OpenCodeSettingsService openCode;
    private final OpenRouterSettingsService openRouter;
    private final CustomProviderSettingsService custom;

    public ModelProvidersController(ModelProvidersService providers, ModelProviderSettingsService active,
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

    public record PatchRequest(String preset, String displayName, String apiFormat, String baseUrl,
                               String apiKey, String selectedModel, String modelSource) {
    }

    public record ProbeRequest(String apiFormat, String baseUrl, String apiKey) {
    }

    public record DiscoveryView(List<String> models, boolean manualModel, String endpointPreview) {
    }

    @GetMapping
    public ProviderListView list() {
        List<ProviderView> views = new ArrayList<>();
        views.add(presetView(ModelProvider.OPENCODE_ZEN));
        views.add(presetView(ModelProvider.OPENROUTER));
        for (ModelProviderRecord record : providers.list()) {
            views.add(rowView(record));
        }
        return new ProviderListView(views, active.activeProviderCode(),
                active.activeProviderId() == null ? null : active.activeProviderId().toString());
    }

    @PostMapping
    public ProviderView create(@RequestBody PatchRequest request) {
        return rowView(providers.create(toPatch(request)));
    }

    @PutMapping("/{id}")
    public ProviderView update(@PathVariable String id, @RequestBody PatchRequest request) {
        return rowView(providers.update(requireRowId(id), toPatch(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        providers.delete(requireRowId(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ProviderListView activate(@PathVariable String id) {
        ModelProviderRecord record = providers.require(requireRowId(id));
        providers.requireActivatable(record);
        active.setActiveTarget(record.preset().name(), record.id());
        return list();
    }

    /** Draft probe: the card may test a base URL / protocol before saving. */
    @PostMapping("/{id}/probe")
    public DiscoveryView probe(@PathVariable String id, @RequestBody ProbeRequest request) {
        String format = request == null ? null : request.apiFormat();
        String baseUrl = request == null ? null : request.baseUrl();
        String apiKey = request == null ? null : request.apiKey();
        return toView(providers.discover(requireRowId(id), format, baseUrl, apiKey));
    }

    /** Lists the catalog with the stored credential; never asks for the key again. */
    @GetMapping("/{id}/models")
    public DiscoveryView models(@PathVariable String id) {
        return toView(providers.listModels(requireRowId(id)));
    }

    @PostMapping("/{id}/validate")
    public ProviderView validate(@PathVariable String id) {
        return rowView(providers.validate(requireRowId(id)));
    }

    private static DiscoveryView toView(ProviderModelCatalogService.Discovery discovery) {
        return new DiscoveryView(discovery.allModels(), discovery.manualModel(), discovery.endpointPreview());
    }

    private static ModelProvidersService.Patch toPatch(PatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        return new ModelProvidersService.Patch(request.preset(), request.displayName(), request.apiFormat(),
                request.baseUrl(), request.apiKey(), request.selectedModel(), request.modelSource());
    }

    /** Row ids are UUIDs; a preset code is never a valid target here. */
    private static UUID requireRowId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown model provider: " + raw);
        }
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

    private ProviderView rowView(ModelProviderRecord record) {
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Request validation failed" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", msg));
    }
}
