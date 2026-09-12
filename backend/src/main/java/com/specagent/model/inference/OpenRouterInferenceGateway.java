package com.specagent.model.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.FragmentListener;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenRouterGatewaySupport;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.settings.openrouter.OpenRouterSettings;
import com.specagent.settings.openrouter.OpenRouterSettingsService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenRouter provider gateway. Fixed base URL, fixed Chat Completions format.
 * No fallback, no format auto-detection.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class OpenRouterInferenceGateway implements ModelInferenceGateway {

    private final OpenRouterSettingsService settings;
    private final ProtocolAdapterRegistry registry;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public OpenRouterInferenceGateway(OpenRouterSettingsService settings, ProtocolAdapterRegistry registry,
                                      ObjectMapper mapper) {
        this.settings = settings;
        this.registry = registry;
        this.mapper = mapper;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        OpenRouterSettings s = settings.requireStored();
        settings.requireActivatable();
        ProtocolAdapter adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String endpoint = OpenRouterGatewaySupport.BASE_URL + "/chat/completions";
        Map<String, Object> body = adapter.buildRequestBody(request, s.selectedModel());
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(
                client, mapper, endpoint, authHeaders(s.apiKey()),
                body, ProviderHttpSupport.INFERENCE_TIMEOUT, "openrouter");
        return adapter.parseNonStreamResponse(result.json(), "openrouter");
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        OpenRouterSettings s = settings.requireStored();
        settings.requireActivatable();
        ProtocolAdapter adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String endpoint = OpenRouterGatewaySupport.BASE_URL + "/chat/completions";
        Map<String, Object> body = new LinkedHashMap<>(adapter.buildRequestBody(request, s.selectedModel()));
        body.put("stream", true);
        String aggregated = ProviderHttpSupport.postSse(client, mapper, endpoint,
                authHeaders(s.apiKey()), body, adapter, "openrouter", listener);
        if (aggregated == null || aggregated.isBlank()) {
            throw ModelProviderException.invalidResponse("openrouter", "OpenRouter returned empty stream");
        }
        return new ModelInferenceResponse(aggregated, "stop", null, null);
    }

    private static Map<String, String> authHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey.trim());
        headers.put("HTTP-Referer", "https://spec-agent.local");
        headers.put("X-Title", "Spec Agent");
        return headers;
    }
}
