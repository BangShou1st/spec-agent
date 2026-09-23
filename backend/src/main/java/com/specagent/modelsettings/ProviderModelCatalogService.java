package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenRouterGatewaySupport;
import com.specagent.model.provider.OpenRouterModelQualification;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Model discovery, one code path per preset kind.
 *
 * <p>This is deliberately the ONLY place that knows how a given preset exposes
 * its catalog: OpenCode Zen and OpenRouter publish a qualified list behind
 * their special transports, while a Custom gateway is probed through the
 * negotiated protocol adapter and degrades to manual entry when it does not
 * implement {@code /models}.
 */
@Service
public class ProviderModelCatalogService {

    private final OpenCodeModelCatalog openCodeCatalog;
    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final HttpClient client;

    public ProviderModelCatalogService(OpenCodeModelCatalog openCodeCatalog, ObjectMapper mapper,
                                       ProtocolAdapterRegistry registry) {
        this.openCodeCatalog = openCodeCatalog;
        this.mapper = mapper;
        this.registry = registry;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    /**
     * One discovery result. {@code manualModel} means the gateway does not
     * expose a list, so the UI must fall back to a typed model id.
     */
    public record Discovery(List<String> allModels, List<String> freeModels,
                            boolean manualModel, String endpointPreview) {
    }

    /**
     * Probes an unsaved candidate configuration. Draft values win over the
     * stored ones so the card can test a base URL / protocol before saving.
     */
    public Discovery probe(String presetCode, String draftApiFormat, String draftBaseUrl,
                           String apiKey, ModelProviderRecord stored) {
        return switch (com.specagent.model.contract.ModelProvider.fromCode(presetCode)) {
            case OPENCODE_ZEN -> discoverOpenCode(apiKey);
            case OPENROUTER -> discoverOpenRouter(apiKey);
            case CUSTOM -> discoverCustom(draftApiFormat, draftBaseUrl, apiKey, stored);
        };
    }

    /** Lists the catalog using the already stored credential. */
    public Discovery list(ModelProviderRecord record) {
        return switch (record.preset()) {
            case OPENCODE_ZEN -> discoverOpenCode(record.apiKey());
            case OPENROUTER -> discoverOpenRouter(record.apiKey());
            case CUSTOM -> discoverCustom(record.apiFormat(), record.baseUrl(), null, record);
        };
    }

    private Discovery discoverOpenCode(String apiKey) {
        List<String> all = openCodeCatalog.listAllModels(apiKey);
        if (all.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode has no currently available models");
        }
        List<String> free = all.stream().filter(OpenCodeModelCatalog::isFreeModel).toList();
        return new Discovery(all, free, false, null);
    }

    private Discovery discoverOpenRouter(String apiKey) {
        var ids = OpenRouterModelQualification.qualifiedModelIds(
                fetchModelRoot(apiKey, "openrouter"), "openrouter");
        List<String> free = ids.all().stream()
                .filter(OpenRouterGatewaySupport::isFreeModelId)
                .toList();
        return new Discovery(ids.all(), free, false, null);
    }

    /**
     * Custom gateways negotiate their own protocol, so discovery runs through
     * the adapter registry and 404/405/501 degrade to manual model entry.
     * {@code apiKey == null} reuses the stored key; blank means unauthenticated.
     */
    private Discovery discoverCustom(String formatCode, String baseUrlInput, String apiKey,
                                     ModelProviderRecord stored) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        String key;
        if (apiKey != null && !apiKey.isBlank()) {
            key = apiKey.trim();
        } else if (apiKey == null && stored != null && stored.hasKey()) {
            key = stored.apiKey();
        } else {
            key = null;
        }
        ProtocolAdapter adapter = registry.require(format);
        String context = "custom-" + format.name().toLowerCase();
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, normalized + "/models", adapter.authHeaders(key),
                ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        String preview = previewEndpoint(formatCode, normalized);
        if (result.status() == 404 || result.status() == 405 || result.status() == 501 || result.json() == null) {
            return new Discovery(List.of(), List.of(), true, preview);
        }
        List<String> ids = adapter.parseModelList(result.json(), context);
        return new Discovery(ids, ids, false, preview);
    }

    /** Presentation-only canonical endpoint; never throws on malformed input. */
    public String previewEndpoint(String formatCode, String baseUrlInput) {
        try {
            return ProviderUrlSecurity.canonicalEndpoint(
                    ProviderUrlSecurity.normalizeBaseUrl(baseUrlInput),
                    CustomApiFormat.fromCode(formatCode));
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode fetchModelRoot(String apiKey, String context) {
        var adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, OpenRouterGatewaySupport.BASE_URL + "/models",
                adapter.authHeaders(apiKey), ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        JsonNode root = result.json();
        if (root == null) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list unsupported");
        }
        return root;
    }
}
