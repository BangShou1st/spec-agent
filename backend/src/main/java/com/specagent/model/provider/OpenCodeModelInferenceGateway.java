package com.specagent.model.provider;

import com.specagent.model.contract.ModelInferenceGateway;

import com.specagent.model.contract.RuntimeOpenCodeSettings;

import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;

import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeChatMessage;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenSessionIds;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:OpenCodeModelInferenceGateway.java
 *
 * 用途:由冻结的 OpenCode Zen 传输层支撑的真实 {@link ModelInferenceGateway}。
 * 凭据解析和 HTTP 传输保持在原处不动;本适配器只是把调用重塑为提供商无关的
 * 推理端口,让 Python 大脑可以通过内部代理使用同一套经过验证的传输层,而全程
 * 接触不到密钥。模型选择在保存设置时对照提供商的实时模型列表校验;运行时路径
 * 不附加任何额外的成本策略过滤。
 *
 * 不重试,不做提供商降级(fallback)。生成上限不向下转发:生产 OpenCode
 * 补全保持已验证的请求形态。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class OpenCodeModelInferenceGateway implements ModelInferenceGateway {

    private final OpenCodeZenTransport transport;
    private final OpenCodeRuntimeSettingsPort settingsService;

    public OpenCodeModelInferenceGateway(OpenCodeZenTransport transport,
                                         OpenCodeRuntimeSettingsPort settingsService) {
        this.transport = transport;
        this.settingsService = settingsService;
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
        String selectedModel = settings.selectedModel();
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model is selected");
        }
        List<OpenCodeChatMessage> messages = request.messages().stream()
                .map(message -> new OpenCodeChatMessage(message.role(), message.content()))
                .toList();
        OpenCodeChatCompletionRequest completionRequest =
                toProviderRequest(selectedModel, messages, request.outputContract());
        OpenCodeCompletionResponse completion =
                transport.complete(settings.apiKey(),
                        OpenCodeZenSessionIds.forConversation(request.conversationOrRun()),
                        completionRequest);
        return toResponse(completion);
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
            com.specagent.model.contract.FragmentListener listener) {
        RuntimeOpenCodeSettings settings = settingsService.requireRuntimeSettings();
        String selectedModel = settings.selectedModel();
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "No OpenCode model is selected");
        }
        List<OpenCodeChatMessage> messages = request.messages().stream()
                .map(message -> new OpenCodeChatMessage(message.role(), message.content()))
                .toList();
        OpenCodeChatCompletionRequest completionRequest =
                toProviderRequest(selectedModel, messages, request.outputContract());
        OpenCodeCompletionResponse completion =
                transport.completeStreaming(settings.apiKey(),
                        OpenCodeZenSessionIds.forConversation(request.conversationOrRun()),
                        completionRequest, listener);
        return toResponse(completion);
    }

    private static ModelInferenceResponse toResponse(OpenCodeCompletionResponse completion) {
        return new ModelInferenceResponse(
                completion.content(),
                completion.finishReason(),
                completion.promptTokens(),
                completion.completionTokens());
    }

    /**
     * 把提供商无关的输出契约翻译成 OpenCode 原生的请求形态。Text 保持历史的线上
     * 形态;JSON 对象转换为 {@code response_format.type=json_object},不承诺原生
     * schema 强制;JSON schema 转换为 {@code response_format.json_schema} 并启用
     * strict 强制。未知的契约变体按失败处理,而不是悄悄降级为文本。各模式之间
     * 不做静默回退。
     */
    private static OpenCodeChatCompletionRequest toProviderRequest(
            String selectedModel,
            List<OpenCodeChatMessage> messages,
            ModelOutputContract outputContract) {
        if (outputContract instanceof ModelOutputContract.JsonSchema jsonSchema) {
            Map<String, Object> schemaWrapper = new LinkedHashMap<>();
            schemaWrapper.put("name", jsonSchema.name());
            schemaWrapper.put("strict", true);
            schemaWrapper.put("schema", jsonSchema.schema());
            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_schema");
            responseFormat.put("json_schema", schemaWrapper);
            return new OpenCodeChatCompletionRequest(selectedModel, messages, responseFormat);
        }
        if (outputContract instanceof ModelOutputContract.JsonObject) {
            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_object");
            return new OpenCodeChatCompletionRequest(selectedModel, messages, responseFormat);
        }
        if (outputContract instanceof ModelOutputContract.Text) {
            return new OpenCodeChatCompletionRequest(selectedModel, messages);
        }
        throw new IllegalArgumentException(
                "Unsupported ModelOutputContract: " + outputContract);
    }
}
