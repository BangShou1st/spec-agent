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



    public void probeCustom(CustomApiFormat format, String normalizedBaseUrl, String apiKey, String model) {
        String context = "custom-" + format.name().toLowerCase();
        // MS-C3:ANTHROPIC_MESSAGES 通过宿主 JSON Envelope 桥接承载同一份
        // FINAL 决策探测——与 Chat/Responses 共用完全相同的权威决策语义。
        String m = ProviderUrlSecurity.normalizeModelId(model);
        ProtocolAdapter adapter = registry.require(format);
        String endpoint = ProviderUrlSecurity.canonicalEndpoint(normalizedBaseUrl, format);
        runProbe(adapter, endpoint, apiKey == null ? null : apiKey.trim(), m, context);
    }

    public void probeChat(CustomApiFormat format, String baseUrl, String apiKey, String model) {
        ProtocolAdapter adapter = registry.require(format);
        var request = new ModelInferenceRequest(UUID.randomUUID(), "compatibility-chat",
                List.of(new ModelInferenceMessage("user", "Reply with probe ok.")),
                64, ModelOutputContract.text());
        var result = ProviderHttpSupport.postJson(client, mapper,
                ProviderUrlSecurity.canonicalEndpoint(baseUrl, format), adapter.authHeaders(apiKey),
                adapter.buildRequestBody(request, model), ProviderHttpSupport.SETTINGS_TIMEOUT, "custom-probe");
        var response = adapter.parseNonStreamResponse(result.json(), "custom-probe");
        if (response.content() == null || response.content().isBlank()) {
            throw ModelProviderException.invalidResponse("custom-probe", "Chat probe returned empty content");
        }
    }

    /** A harmless local tool fixture; never invokes the application's tools. */
    public void probeNativeTools(CustomApiFormat format, String baseUrl, String apiKey, String model) {
        if (format != CustomApiFormat.CHAT_COMPLETIONS && format != CustomApiFormat.RESPONSES
                && format != CustomApiFormat.ANTHROPIC_MESSAGES) {
            throw ModelProviderException.invalidResponse("custom-native-probe", "Unsupported native tool protocol");
        }
        new NativeToolRoundTripVerifier().verify(request -> {
            if (format == CustomApiFormat.RESPONSES) {
                UUID providerId = UUID.randomUUID();
                var runtimeSettings = new com.specagent.model.contract.RuntimeCustomSettings(
                        format.name(), baseUrl, apiKey, model, 1, providerId, true);
                var responsesTransport = new ResponsesGaNativeTransport(() -> runtimeSettings, mapper, client);
                return responsesTransport.complete(new com.specagent.model.contract.GaModelTarget(
                        "CUSTOM", model, runtimeSettings.bindingRevision(), apiKey), request,
                        ProviderHttpSupport.SETTINGS_TIMEOUT, () -> true);
            }
            if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
                // 双轮资格验证必须走生产 Anthropic 传输与适配器,绝不使用旁路实现。
                UUID providerId = UUID.randomUUID();
                var runtimeSettings = new com.specagent.model.contract.RuntimeCustomSettings(
                        format.name(), baseUrl, apiKey, model, 1, providerId, true);
                var anthropicTransport = new AnthropicGaNativeTransport(() -> runtimeSettings, mapper, client);
                return anthropicTransport.complete(new com.specagent.model.contract.GaModelTarget(
                        "CUSTOM", model, runtimeSettings.bindingRevision(), apiKey), request,
                        ProviderHttpSupport.SETTINGS_TIMEOUT, () -> true);
            }
            var adapter = new GaChatCompletionsAdapter();
            String endpoint = ProviderUrlSecurity.canonicalEndpoint(baseUrl, format);
            var headers = registry.require(format).authHeaders(apiKey);
            var result = ProviderHttpSupport.postJson(client, mapper, endpoint, headers,
                    adapter.requestBody(request, model), ProviderHttpSupport.SETTINGS_TIMEOUT, "custom-native-probe");
            return adapter.parse(result.json().toString());
        });
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
                ProviderHttpSupport.INFERENCE_TIMEOUT, context);
        // 契约感知解析:Anthropic 在此解包宿主 JSON Envelope 后才交给决策语义校验。
        var response = adapter.parseNonStreamResponse(result.json(), context,
                ModelOutputContract.jsonObject());
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
