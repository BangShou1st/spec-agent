package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:CompatibilityProbeService.java
 *
 * 用途:激活前的硬性门槛:执行一次最小、有界的推理,要求模型返回合法的
 * 最小 FINAL 决策。探测绝不执行任何 Agent 工具。用于保存/激活提供商配置前
 * 验证凭据、端点和模型确实可用。
 */
@Service
public class CompatibilityProbeService {

    private static final String PROBE_SYSTEM = "Return exactly one JSON object with kind FINAL.";
    private static final String PROBE_USER =
            "Return only {\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"} with no other text.";

    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final CompatibilityDecisionSemantics decisionSemantics;
    private final HttpClient client;

    public CompatibilityProbeService(ObjectMapper mapper, ProtocolAdapterRegistry registry,
                                     CompatibilityDecisionSemantics decisionSemantics) {
        this.mapper = mapper;
        this.registry = registry;
        this.decisionSemantics = decisionSemantics;
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
        Object decision;
        try {
            decision = decisionSemantics.parseDecision(response.content());
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context,
                    "Compatibility probe did not return a valid FINAL decision: " + ex.getMessage());
        }
        try {
            decisionSemantics.validateDecision(decision);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context,
                    "Compatibility probe output rejected by authoritative validation: " + ex.getMessage());
        }
        if (!decisionSemantics.isFinal(decision)) {
            throw ModelProviderException.invalidResponse(context, "Compatibility probe must return FINAL");
        }
    }
}
