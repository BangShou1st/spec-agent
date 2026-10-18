package com.specagent.model.provider;

import com.specagent.model.contract.ModelInferenceGateway;

import com.specagent.model.contract.RuntimeOpenRouterSettings;

import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.OpenRouterRuntimeSettingsPort;
import com.specagent.model.provider.OpenRouterInferenceGateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenRouterGatewaySupport;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文件名:OpenRouterInferenceGateway.java
 *
 * 用途:OpenRouter 提供商的推理网关。固定 base URL,固定使用 Chat Completions
 * 格式。不做降级(fallback),不做格式自动探测。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class OpenRouterInferenceGateway implements ModelInferenceGateway {

    private final OpenRouterRuntimeSettingsPort settings;
    private final ProtocolAdapterRegistry registry;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public OpenRouterInferenceGateway(OpenRouterRuntimeSettingsPort settings, ProtocolAdapterRegistry registry,
                                      ObjectMapper mapper) {
        this.settings = settings;
        this.registry = registry;
        this.mapper = mapper;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        RuntimeOpenRouterSettings s = settings.requireRuntimeSettings();
        return doComplete(request, s.selectedModel(), s.apiKey());
    }

    /**
     * 用调用方已解析的<b>冻结目标模型</b>发起请求(路由网关专用)。凭据仍从运行时
     * 设置读取,但模型 id 只使用传入值——本次请求内绝不重新读取所选模型。
     */
    public ModelInferenceResponse complete(ModelInferenceRequest request, String selectedModel) {
        RuntimeOpenRouterSettings s = settings.requireRuntimeSettings();
        return doComplete(request, selectedModel, s.apiKey());
    }

    private ModelInferenceResponse doComplete(ModelInferenceRequest request,
                                              String selectedModel, String apiKey) {
        ProtocolAdapter adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String endpoint = OpenRouterGatewaySupport.BASE_URL + "/chat/completions";
        Map<String, Object> body = adapter.buildRequestBody(request, selectedModel);
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(
                client, mapper, endpoint, authHeaders(apiKey),
                body, ProviderHttpSupport.INFERENCE_TIMEOUT, "openrouter");
        return adapter.parseNonStreamResponse(result.json(), "openrouter");
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        RuntimeOpenRouterSettings s = settings.requireRuntimeSettings();
        return doCompleteStreaming(request, listener, s.selectedModel(), s.apiKey());
    }

    /** 流式版本的冻结目标重载;语义与 {@link #complete(ModelInferenceRequest, String)} 相同。 */
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
                                                    FragmentListener listener, String selectedModel) {
        RuntimeOpenRouterSettings s = settings.requireRuntimeSettings();
        return doCompleteStreaming(request, listener, selectedModel, s.apiKey());
    }

    private ModelInferenceResponse doCompleteStreaming(ModelInferenceRequest request,
                                                       FragmentListener listener,
                                                       String selectedModel, String apiKey) {
        ProtocolAdapter adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String endpoint = OpenRouterGatewaySupport.BASE_URL + "/chat/completions";
        Map<String, Object> body = new LinkedHashMap<>(adapter.buildRequestBody(request, selectedModel));
        body.put("stream", true);
        String aggregated = ProviderHttpSupport.postSse(client, mapper, endpoint,
                authHeaders(apiKey), body, adapter, "openrouter", listener);
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
