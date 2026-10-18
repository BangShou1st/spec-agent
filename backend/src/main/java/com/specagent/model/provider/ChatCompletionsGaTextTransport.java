package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.StreamCancelledException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * 文件名:ChatCompletionsGaTextTransport.java
 *
 * 用途:OpenRouter 与 Custom(Chat Completions 格式)共用的 GA 纯文本传输。
 *
 * <p><b>只承担协议转换职责,绝不是第二套模型客户端</b>:它把 GA 的
 * {@link GaModelContract.Request} 直接交给既有的 {@link GaChatCompletionsAdapter}
 * 生成 OpenAI 兼容的 Chat Completions 载荷,并复用既有的
 * {@link ProviderHttpSupport}(有界请求体、超时、不跟随重定向、错误分类与脱敏)
 * 与既有的 {@link ProviderUrlSecurity}(Custom 的 SSRF / 安全源校验)。
 *
 * <p>这两个提供商尚未取得已验证的原生工具传输,因此这里的唯一合法形态就是
 * {@link com.specagent.assistant.runtime.GaExecutionMode#TEXT_CHAT}:任何携带 tools 或
 * assistant/tool 工具历史的请求都以 {@link UnsupportedOperationException} 失败关闭,
 * 绝不模拟 function calling,也绝不把工具角色发给不支持的模型,更不切换到其他提供商。
 *
 * <p>不重试、不换模型、不换提供商:一次调用一次请求。凭据只在此处作为 Authorization
 * 头出现,绝不进入异常消息或日志。
 */
@Component
public class ChatCompletionsGaTextTransport {

    private final CustomRuntimeSettingsPort customSettings;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatCompletionsGaTextTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper) {
        this(customSettings, mapper, ProviderHttpSupport.newClient(Duration.ofSeconds(10)));
    }

    /** 包私有构造:同一策略,注入客户端供确定性传输层测试使用。 */
    ChatCompletionsGaTextTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper,
                                   HttpClient client) {
        this.customSettings = customSettings;
        this.mapper = mapper;
        this.client = client;
    }

    /** 非流式入口:一次有界的 Chat Completions 调用,响应经
     *  {@link GaChatCompletionsAdapter#validateResponse} 完整校验后才返回。 */
    public GaModelContract.Response complete(GaModelTarget target, GaModelContract.Request request,
                                             Duration remainingBudget, BooleanSupplier active) {
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero()
                || remainingBudget.compareTo(Duration.ofSeconds(180)) > 0) {
            throw new IllegalArgumentException("Invalid GA execution deadline");
        }
        if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
        requireTextOnly(request);
        Resolved resolved = resolve(target);
        GaChatCompletionsAdapter adapter = new GaChatCompletionsAdapter();
        Map<String, Object> body = adapter.requestBody(request, target.model());
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(client, mapper,
                resolved.endpoint(), resolved.headers(), body, ProviderHttpSupport.INFERENCE_TIMEOUT,
                resolved.context());
        String json;
        try {
            json = mapper.writeValueAsString(result.json());
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(resolved.context(),
                    "Failed to re-encode the GA text response");
        }
        return adapter.validateResponse(request, adapter.parse(json));
    }

    /**
     * 流式入口的纯文本形态:底层是一次非流式调用,完成后把完整正文作为单个片段投递给
     * 监听器(监听器拒绝即按取消处理)。绝不新增第二套 SSE 解析器。
     */
    public GaModelContract.Response stream(GaModelTarget target, GaModelContract.Request request,
                                           Duration remainingBudget, BooleanSupplier active,
                                           FragmentListener listener) {
        if (listener == null) throw new IllegalArgumentException("GA text listener required");
        GaModelContract.Response response = complete(target, request, remainingBudget, active);
        if (!response.content().isEmpty() && !listener.onFragment(response.content())) {
            throw new StreamCancelledException("GA execution no longer active");
        }
        return response;
    }

    /** 纯文本形态的硬性前置条件:不携带 tools、也不携带 assistant/tool 工具历史。 */
    static void requireTextOnly(GaModelContract.Request request) {
        boolean carriesTools = !request.tools().isEmpty()
                || request.messages().stream().anyMatch(message ->
                        !message.toolCalls().isEmpty() || message.toolCallId() != null);
        if (carriesTools) throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
    }

    private Resolved resolve(GaModelTarget target) {
        ModelProvider provider;
        try {
            provider = ModelProvider.fromCode(target.provider());
        } catch (IllegalArgumentException unknownProvider) {
            throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        }
        return switch (provider) {
            case OPENROUTER -> {
                // 固定 base URL、固定 Chat Completions;凭据取<b>已冻结</b>的 target,
                // 绝不重新读取设置里的密钥——运行中切换提供商/模型不会改变本 Run。
                Map<String, String> headers = new LinkedHashMap<>(
                        new ChatCompletionsProtocolAdapter().authHeaders(target.credential()));
                headers.put("HTTP-Referer", "https://spec-agent.local");
                headers.put("X-Title", "Spec Agent");
                yield new Resolved(OpenRouterGatewaySupport.BASE_URL + "/chat/completions",
                        headers, "openrouter");
            }
            case CUSTOM -> {
                RuntimeCustomSettings settings = customSettings.requireRuntimeSettings();
                CustomApiFormat format = CustomApiFormat.fromCode(settings.apiFormat());
                if (format != CustomApiFormat.CHAT_COMPLETIONS) {
                    throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
                }
                // 绝不放宽自定义 URL 的 SSRF / 安全源检查:端点仍走既有的 fail-closed 校验。
                String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(settings.baseUrl());
                yield new Resolved(ProviderUrlSecurity.canonicalEndpoint(normalized, format),
                        new ChatCompletionsProtocolAdapter().authHeaders(target.credential()),
                        "custom-chat_completions");
            }
            default -> throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        };
    }

    private record Resolved(String endpoint, Map<String, String> headers, String context) {
    }
}
