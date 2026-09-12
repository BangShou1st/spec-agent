package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.globalassistant.model.GlobalAssistantDecision;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.model.inference.ModelInferenceMessage;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelOutputContract;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Activation hard gate: minimal bounded inference requiring a legal minimal
 * FINAL decision. Never executes Agent tools.
 */
@Service
public class CompatibilityProbeService {

    private static final String PROBE_SYSTEM = "Return exactly one JSON object with kind FINAL.";
    private static final String PROBE_USER =
            "Return only {\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"} with no other text.";

    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final GlobalAssistantDecisionParser parser;
    private final GlobalAssistantDecisionValidator validator;
    private final HttpClient client;

    public CompatibilityProbeService(ObjectMapper mapper, ProtocolAdapterRegistry registry,
                                     GlobalAssistantDecisionParser parser,
                                     GlobalAssistantDecisionValidator validator) {
        this.mapper = mapper;
        this.registry = registry;
        this.parser = parser;
        this.validator = validator;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    public void probeOpenRouter(String apiKey, String model) {
        String context = "openrouter";
        if (apiKey == null || apiKey.isBlank()) {
            throw ModelProviderException.notConfigured(context, "OpenRouter API key is required");
        }
        String m = ProviderUrlSecurity.normalizeModelId(model);
        ProtocolAdapter adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String endpoint = OpenRouterGatewaySupport.BASE_URL + "/chat/completions";
        runProbe(adapter, endpoint, apiKey.trim(), m, context);
    }

    public void probeCustom(CustomApiFormat format, String normalizedBaseUrl, String apiKey, String model) {
        String context = "custom-" + format.name().toLowerCase();
        if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
            throw ModelProviderException.providerRequestError(context,
                    "Anthropic Messages cannot serve the required JSON_OBJECT contract in V1", null);
        }
        String m = ProviderUrlSecurity.normalizeModelId(model);
        ProtocolAdapter adapter = registry.require(format);
        String endpoint = ProviderUrlSecurity.canonicalEndpoint(normalizedBaseUrl, format);
        runProbe(adapter, endpoint, apiKey == null ? null : apiKey.trim(), m, context);
    }

    private void runProbe(ProtocolAdapter adapter, String endpoint, String apiKey,
                          String model, String context) {
        ModelInferenceRequest request = new ModelInferenceRequest(
                UUID.randomUUID(),
                "compatibility-probe",
                List.of(
                        new ModelInferenceMessage("system", PROBE_SYSTEM),
                        new ModelInferenceMessage("user", PROBE_USER)),
                256,
                ModelOutputContract.jsonObject());
        Map<String, Object> body = adapter.buildRequestBody(request, model);
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(
                client, mapper, endpoint, adapter.authHeaders(apiKey), body,
                Duration.ofSeconds(30), context);
        var response = adapter.parseNonStreamResponse(result.json(), context);
        if (response.content() == null || response.content().isBlank()) {
            throw ModelProviderException.invalidResponse(context, "Compatibility probe returned empty content");
        }
        GlobalAssistantDecision decision;
        try {
            decision = parser.parse(response.content());
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context,
                    "Compatibility probe did not return a valid FINAL decision: " + ex.getMessage());
        }
        try {
            validator.validate(decision);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context,
                    "Compatibility probe output rejected by authoritative validation: " + ex.getMessage());
        }
        if (decision.kind() != GlobalAssistantDecision.DecisionKind.FINAL) {
            throw ModelProviderException.invalidResponse(context, "Compatibility probe must return FINAL");
        }
    }
}
