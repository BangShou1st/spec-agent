package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.contract.StreamCancelledException;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文件名:KiloInferenceGateway.java
 *
 * 用途:Kilo Gateway(OpenAI 兼容,{@code https://ai-gateway.kilo.ai/api/v1})提供商的
 * 推理网关,由 Starter 提供的 {@link KiloGatewayClient} 支撑。凭据与模型来自
 * {@link KiloRuntimeSettingsPort};每次调用构造一个携带该(密钥, 模型)对的客户端,
 * 但复用同一个 HTTP 连接池。
 *
 * <p>不重试、不做提供商降级:Starter 的 fallback 只在配置了备用模型时才会尝试,
 * 本项目不配置备用模型,因此不存在隐式回退。密钥绝不进入异常消息或日志。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class KiloInferenceGateway implements ModelInferenceGateway {

    private static final String CONTEXT = "kilo";

    private final KiloRuntimeSettingsPort settings;
    private final KiloGatewayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public KiloInferenceGateway(KiloRuntimeSettingsPort settings, KiloGatewayProperties properties,
                                ObjectMapper mapper) {
        this.settings = settings;
        this.properties = properties;
        this.mapper = mapper;
        this.http = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        RuntimeKiloSettings runtime = settings.requireRuntimeSettings();
        return doComplete(request, runtime.selectedModel(), runtime.apiKey());
    }

    /**
     * 用调用方已解析的<b>冻结目标模型</b>发起请求(路由网关专用)。凭据仍从运行时
     * 设置读取,但模型 id 只使用传入值——本网关在此次请求内绝不重新读取所选模型,
     * 因此设置页在请求构造期间发生的模型切换不会让本 Run 改送到新模型。
     */
    public ModelInferenceResponse complete(ModelInferenceRequest request, String selectedModel) {
        RuntimeKiloSettings runtime = settings.requireRuntimeSettings();
        return doComplete(request, selectedModel, runtime.apiKey());
    }

    private ModelInferenceResponse doComplete(ModelInferenceRequest request, String model, String apiKey) {
        ObjectNode body = buildBody(request, model, false);
        try {
            return toResponse(client(apiKey).chat(body));
        } catch (KiloGatewayException error) {
            throw translate(error);
        }
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        RuntimeKiloSettings runtime = settings.requireRuntimeSettings();
        return doCompleteStreaming(request, listener, runtime.selectedModel(), runtime.apiKey());
    }

    /** 流式版本的冻结目标重载;语义与 {@link #complete(ModelInferenceRequest, String)} 相同。 */
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
                                                    FragmentListener listener, String selectedModel) {
        RuntimeKiloSettings runtime = settings.requireRuntimeSettings();
        return doCompleteStreaming(request, listener, selectedModel, runtime.apiKey());
    }

    private ModelInferenceResponse doCompleteStreaming(ModelInferenceRequest request,
                                                       FragmentListener listener, String model, String apiKey) {
        ObjectNode body = buildBody(request, model, true);
        StringBuilder aggregated = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<Integer> promptTokens = new AtomicReference<>();
        AtomicReference<Integer> completionTokens = new AtomicReference<>();
        try {
            client(apiKey).streamChat(body, chunk -> {
                JsonNode choice = chunk.path("choices").path(0);
                String delta = choice.path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    aggregated.append(delta);
                    if (!listener.onFragment(delta)) {
                        throw new StreamCancelledException("listener declined content");
                    }
                }
                if (choice.hasNonNull("finish_reason")) {
                    finishReason.set(choice.path("finish_reason").asText());
                }
                JsonNode usage = chunk.path("usage");
                if (usage.hasNonNull("prompt_tokens")) {
                    promptTokens.set(usage.path("prompt_tokens").asInt());
                }
                if (usage.hasNonNull("completion_tokens")) {
                    completionTokens.set(usage.path("completion_tokens").asInt());
                }
            });
        } catch (KiloGatewayException error) {
            throw translate(error);
        }
        if (aggregated.isEmpty()) {
            throw ModelProviderException.invalidResponse(CONTEXT, "Kilo Gateway returned empty stream");
        }
        return new ModelInferenceResponse(aggregated.toString(), finishReason.get(),
                promptTokens.get(), completionTokens.get());
    }

    private ObjectNode buildBody(ModelInferenceRequest request, String model, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        if (stream) {
            body.put("stream", true);
        }
        ArrayNode messages = body.putArray("messages");
        for (ModelInferenceMessage message : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        applyOutputContract(body, request.outputContract());
        return body;
    }

    /**
     * 把提供商无关的输出契约翻译成 Kilo(OpenAI 兼容)的原生线上字段。未知契约按
     * 失败处理,绝不悄悄降级为文本。
     */
    private void applyOutputContract(ObjectNode body, ModelOutputContract contract) {
        if (contract instanceof ModelOutputContract.JsonSchema jsonSchema) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode wrapper = responseFormat.putObject("json_schema");
            wrapper.put("name", jsonSchema.name());
            wrapper.put("strict", true);
            wrapper.set("schema", mapper.valueToTree(jsonSchema.schema()));
        } else if (contract instanceof ModelOutputContract.JsonObject) {
            body.putObject("response_format").put("type", "json_object");
        } else if (!(contract instanceof ModelOutputContract.Text)) {
            throw new IllegalArgumentException("Unsupported ModelOutputContract: " + contract);
        }
    }

    /**
     * 以注入的共享配置为模板派生本次调用的客户端:复用同一 HTTP 连接池,
     * 只覆盖凭据与"仅免费"策略。保留 base URL/代理/超时,便于本地或代理部署。
     */
    private KiloGatewayClient client(String apiKey) {
        KiloGatewayProperties callProperties = KiloClientProperties.forCredential(properties, apiKey, false);
        return new KiloGatewayClient(callProperties, mapper, http);
    }

    private static ModelInferenceResponse toResponse(JsonNode response) {
        JsonNode choice = response.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        String finishReason = choice.hasNonNull("finish_reason")
                ? choice.path("finish_reason").asText() : null;
        JsonNode usage = response.path("usage");
        Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.hasNonNull("completion_tokens")
                ? usage.path("completion_tokens").asInt() : null;
        return new ModelInferenceResponse(content, finishReason, promptTokens, completionTokens);
    }

    private static ModelProviderException translate(KiloGatewayException error) {
        int status = error.getStatusCode();
        if (status == 401 || status == 403) {
            return ModelProviderException.authentication(CONTEXT, error.getMessage(), status);
        }
        if (status == 429) {
            return ModelProviderException.rateLimited(CONTEXT, error.getMessage());
        }
        if (status >= 500) {
            return ModelProviderException.serverError(CONTEXT, error.getMessage(), status);
        }
        if (status < 0) {
            return ModelProviderException.connection(CONTEXT, error.getMessage(), error);
        }
        return ModelProviderException.providerRequestError(CONTEXT, error.getMessage(), status);
    }
}
