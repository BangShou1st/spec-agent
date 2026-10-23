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
 * 用途:其他 Provider 与 Custom(Chat Completions 格式)共用的 GA 纯文本传输。
 *
 * <p><b>只承担协议转换职责,绝不是第二套模型客户端</b>:它把 GA 的
 * {@link GaModelContract.Request} 直接交给既有的 {@link GaChatCompletionsAdapter}
 * 生成 OpenAI 兼容的 Chat Completions 载荷,并复用既有的
 * {@link ProviderHttpSupport}(有界请求体、超时、不跟随重定向、错误分类与脱敏)
 * 与既有的 {@link ProviderUrlSecurity}(Custom 的 SSRF / 安全源校验)。
 *
 * <p>默认仅允许纯文本。Custom 原生工具必须显式登记精确的来源地址与模型，
 * 并且当前配置修订号和冻结绑定一致；其余携带 tools 或工具历史的请求仍由
 * requireTextOnly 失败关闭。资格不通过模型名称推断，也不模拟 function calling。
 *
 * <p>不重试、不换模型、不换提供商:一次调用一次请求。凭据只在此处作为 Authorization
 * 头出现,绝不进入异常消息或日志。
 */
@Component
public class ChatCompletionsGaTextTransport {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatCompletionsGaTextTransport.class);
    private final java.util.concurrent.atomic.AtomicReference<GaHttpSafetyDiagnostic> lastDiagnostic =
            new java.util.concurrent.atomic.AtomicReference<>();

    public GaHttpSafetyDiagnostic lastSafetyDiagnostic() {
        return lastDiagnostic.get();
    }

    private final CustomRuntimeSettingsPort customSettings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final com.specagent.model.contract.CustomNativeToolQualification qualification;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatCompletionsGaTextTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper,
            com.specagent.model.contract.CustomNativeToolQualification qualification) {
        this(customSettings, mapper, ProviderHttpSupport.newClient(Duration.ofSeconds(10)),qualification);
    }

    /** 包私有构造:同一策略,注入客户端供确定性传输层测试使用。 */
    ChatCompletionsGaTextTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper,
                                   HttpClient client) {
        this(customSettings,mapper,client,new com.specagent.model.contract.CustomNativeToolQualification() {
            public boolean hasCustomTargets() {return false;}
            public boolean customQualified(String base,String model) {return false;}
        });
    }
    ChatCompletionsGaTextTransport(CustomRuntimeSettingsPort customSettings,ObjectMapper mapper,HttpClient client,
            com.specagent.model.contract.CustomNativeToolQualification qualification) {
        this.customSettings = customSettings;
        this.mapper = mapper;
        this.client = client;
        this.qualification=qualification;
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
        if (!target.provider().equals("CUSTOM")
                || (!target.settingsRevision().startsWith("custom-provider:") && !qualification.hasCustomTargets())) requireTextOnly(request);
        Resolved resolved = resolve(target);
        if(!resolved.nativeTools()) requireTextOnly(request);
        GaChatCompletionsAdapter adapter = new GaChatCompletionsAdapter();
        Map<String, Object> body = adapter.requestBody(request, target.model());
        // This existing buffered transport returns a complete provider-neutral response.
        body.put("stream",false);
        try {
            ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(client, mapper,
                    resolved.endpoint(), resolved.headers(), body,
                    remainingBudget.compareTo(ProviderHttpSupport.INFERENCE_TIMEOUT)<0?remainingBudget:ProviderHttpSupport.INFERENCE_TIMEOUT,
                    resolved.context());
            String json;
            try {
                json = mapper.writeValueAsString(result.json());
            } catch (Exception ex) {
                throw ModelProviderException.invalidResponse(resolved.context(),
                        "Failed to re-encode the GA text response");
            }
            GaModelContract.Response response = adapter.validateResponse(request, adapter.parse(json));
            GaHttpSafetyDiagnostic diag = GaHttpSafetyDiagnostic.capture(
                    resolved.context(), target.model(), "chat_completions",
                    200, "NONE", "SUCCEEDED", request);
            lastDiagnostic.set(diag);
            return response;
        } catch (ModelProviderException ex) {
            GaHttpSafetyDiagnostic diag = GaHttpSafetyDiagnostic.capture(
                    resolved.context(), target.model(), "chat_completions",
                    ex.httpStatus(), ex.gatewayCategory().name(), "UPSTREAM_REQUEST_FAILED", request);
            lastDiagnostic.set(diag);
            log.warn("GA native HTTP request failed diagnostic: {}", diag);
            throw ex;
        } catch (RuntimeException ex) {
            GaHttpSafetyDiagnostic diag = GaHttpSafetyDiagnostic.capture(
                    resolved.context(), target.model(), "chat_completions",
                    null, ex.getClass().getSimpleName(), "LOCAL_FAILURE", request);
            lastDiagnostic.set(diag);
            throw ex;
        }
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
            case CUSTOM -> {
                RuntimeCustomSettings settings = customSettings.requireRuntimeSettings(target.settingsRevision());
                if (!settings.selectedModel().equals(target.model())
                        || !settings.bindingRevision().equals(target.settingsRevision())
                        || !java.util.Objects.equals(settings.apiKey(),target.credential())) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                CustomApiFormat format = CustomApiFormat.fromCode(settings.apiFormat());
                if (format != CustomApiFormat.CHAT_COMPLETIONS) {
                    throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
                }
                // 绝不放宽自定义 URL 的 SSRF / 安全源检查:端点仍走既有的 fail-closed 校验。
                String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(settings.baseUrl());
                boolean nativeTools=settings.providerId() != null ? settings.nativeToolsVerified()
                        : qualification.customQualified(normalized,target.model());
                if(nativeTools && (!settings.selectedModel().equals(target.model())
                        || !target.settingsRevision().equals(settings.bindingRevision())))
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                yield new Resolved(ProviderUrlSecurity.canonicalEndpoint(normalized, format),
                        new ChatCompletionsProtocolAdapter().authHeaders(target.credential()),
                        "custom-chat_completions",nativeTools);
            }
            default -> throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        };
    }

    private record Resolved(String endpoint, Map<String, String> headers, String context,boolean nativeTools) {
    }
}
