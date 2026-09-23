package com.specagent.modelsettings;

import com.specagent.modelsettings.ModelProviderRecord;
import com.specagent.modelsettings.ModelProviderViewService;
import com.specagent.modelsettings.ModelProviderViewService.ProviderView;
import com.specagent.modelsettings.ModelProvidersService;
import com.specagent.modelsettings.ProviderModelCatalogService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>This is the extension point. Adding a provider is a POST here, never a
 * new controller, table or enum value. Presets stay projected in
 * {@link ModelProviderViewService} because their request shapes are special —
 * OpenCode Zen issues absolute direct calls with extra headers, and OpenRouter
 * runs a qualification pass — but to the settings page they are just two more
 * cards in the same list.
 */
@RestController
@RequestMapping("/api/v1/settings/providers")
public class ModelProvidersController {

    private final ModelProvidersService providers;
    private final ModelProviderViewService views;

    public ModelProvidersController(ModelProvidersService providers, ModelProviderViewService views) {
        this.providers = providers;
        this.views = views;
    }

    public record PatchRequest(String preset, String displayName, String apiFormat, String baseUrl,
                               String apiKey, String selectedModel, String modelSource) {
    }

    public record ProbeRequest(String apiFormat, String baseUrl, String apiKey) {
    }

    public record DiscoveryView(List<String> models, boolean manualModel, String endpointPreview) {
    }

    @GetMapping
    public ModelProviderViewService.ProviderListView list() {
        return views.listView();
    }

    @PostMapping
    public ProviderView create(@RequestBody PatchRequest request) {
        return views.rowView(providers.create(toPatch(request)));
    }

    @PutMapping("/{id}")
    public ProviderView update(@PathVariable String id, @RequestBody PatchRequest request) {
        return views.rowView(providers.update(requireRowId(id), toPatch(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        providers.delete(requireRowId(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ModelProviderViewService.ProviderListView activate(@PathVariable String id) {
        ModelProviderRecord record = providers.require(requireRowId(id));
        providers.requireActivatable(record);
        providers.activate(record);
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
        return views.rowView(providers.validate(requireRowId(id)));
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
}
