package com.specagent.model.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.FragmentListener;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.model.provider.ProviderUrlSecurity;
import com.specagent.settings.custom.CustomProviderSettings;
import com.specagent.settings.custom.CustomProviderSettingsService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Custom provider gateway. API format is explicit user choice; never
 * auto-detected, never fallen back to another format.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class CustomInferenceGateway implements ModelInferenceGateway {

    private final CustomProviderSettingsService settings;
    private final ProtocolAdapterRegistry registry;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public CustomInferenceGateway(CustomProviderSettingsService settings, ProtocolAdapterRegistry registry,
                                  ObjectMapper mapper) {
        this.settings = settings;
        this.registry = registry;
        this.mapper = mapper;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        Resolved resolved = resolve();
        Map<String, Object> body = resolved.adapter().buildRequestBody(request, resolved.model());
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(
                client, mapper, resolved.endpoint(), resolved.adapter().authHeaders(resolved.apiKey()),
                body, ProviderHttpSupport.INFERENCE_TIMEOUT, resolved.context());
        return resolved.adapter().parseNonStreamResponse(result.json(), resolved.context());
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        Resolved resolved = resolve();
        Map<String, Object> body = new LinkedHashMap<>(
                resolved.adapter().buildRequestBody(request, resolved.model()));
        // Anthropic streaming uses stream:true inside the same body shape;
        // Chat/Responses use stream:true as well.
        body.put("stream", true);
        String aggregated = ProviderHttpSupport.postSse(client, mapper, resolved.endpoint(),
                resolved.adapter().authHeaders(resolved.apiKey()), body,
                resolved.adapter(), resolved.context(), listener);
        if (aggregated == null || aggregated.isBlank()) {
            throw ModelProviderException.invalidResponse(resolved.context(), "Custom provider returned empty stream");
        }
        return new ModelInferenceResponse(aggregated, "stop", null, null);
    }

    private Resolved resolve() {
        CustomProviderSettings s = settings.requireStored();
        settings.requireActivatable();
        CustomApiFormat format = CustomApiFormat.fromCode(s.apiFormat());
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(s.baseUrl());
        String endpoint = ProviderUrlSecurity.canonicalEndpoint(normalized, format);
        ProtocolAdapter adapter = registry.require(format);
        String context = "custom-" + format.name().toLowerCase();
        return new Resolved(adapter, endpoint, s.apiKey(), s.selectedModel(), context);
    }

    private record Resolved(ProtocolAdapter adapter, String endpoint, String apiKey, String model, String context) {
    }
}
