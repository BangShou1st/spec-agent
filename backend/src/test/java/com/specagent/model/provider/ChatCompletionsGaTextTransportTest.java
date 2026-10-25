package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.RuntimeCustomSettings;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 文件名:ChatCompletionsGaTextTransportTest.java
 *
 * 测试目标:OpenRouter / Custom 的 GA 纯文本 Chat Completions 传输——发送<b>不带 tools</b>
 * 的合法请求、解析并返回真实正文、把提供商错误映射为类型化失败,并且对任何携带工具
 * 声明或工具历史的请求失败关闭(绝不模拟 function calling)。
 */
class ChatCompletionsGaTextTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CustomRuntimeSettingsPort custom = mock(CustomRuntimeSettingsPort.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    private static GaModelContract.Request textRequest() {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "你好", null, null)),
                List.of(), "none", 256, false);
    }

    private static GaModelContract.Request toolRequest() {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "你好", null, null)),
                List.of(new GaModelContract.Tool("project_search", "Find projects",
                        Map.of("type", "object", "properties", Map.of()))),
                "auto", 256, false);
    }

    private static HttpResponse<byte[]> chatResponse(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static HttpClient clientReturning(HttpResponse<byte[]> response,
                                              AtomicReference<HttpRequest> captured) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.<byte[]>send(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return response;
        });
        return client;
    }

    private static String validChatBody(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
    }

    @Test
    void customChatCompletionsReturnsRealTextContent() throws Exception {
        when(custom.requireRuntimeSettings()).thenReturn(
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "http://localhost:9/v1", "custom-key", "llama", 1));
        var captured = new AtomicReference<HttpRequest>();
        var transport = new ChatCompletionsGaTextTransport(custom, mapper,
                clientReturning(chatResponse(200, validChatBody("你好,我能聊天")), captured));

        var response = transport.complete(new GaModelTarget("CUSTOM", "llama", "custom-settings:1", "custom-key"),
                textRequest(), Duration.ofSeconds(60), () -> true);

        assertThat(response.content()).isEqualTo("你好,我能聊天");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().uri().toString()).isEqualTo("http://localhost:9/v1/chat/completions");
        assertThat(captured.get().headers().firstValue("Authorization")).contains("Bearer custom-key");
    }

    @Test
    void customCanUseAnOpenRouterCompatibleEndpoint() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        var transport = new ChatCompletionsGaTextTransport(custom, mapper,
                clientReturning(chatResponse(200, validChatBody("ok")), captured));

        when(custom.requireRuntimeSettings()).thenReturn(new RuntimeCustomSettings("CHAT_COMPLETIONS", "https://openrouter.ai/api/v1", "or-key", "fixed-model", 1));
        transport.complete(new GaModelTarget("CUSTOM", "fixed-model", "custom-settings:1", "or-key"),
                textRequest(), Duration.ofSeconds(60), () -> true);

        assertThat(captured.get().uri().toString())
                .isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(captured.get().headers().firstValue("Authorization")).contains("Bearer or-key");
    }

    @Test
    void textOnlyBodyNeverCarriesToolDeclarations() {
        Map<String, Object> body = new GaChatCompletionsAdapter().requestBody(textRequest(), "llama");
        assertThat(body).doesNotContainKey("tools").doesNotContainKey("tool_choice");
        assertThat(body.get("messages")).isNotNull();
        assertThat(body.get("stream")).isEqualTo(false);
    }

    @Test
    void toolCarryingRequestsFailClosedBeforeAnyHttpCall() {
        HttpClient client = mock(HttpClient.class);
        var transport = new ChatCompletionsGaTextTransport(custom, mapper, client);

        assertThatThrownBy(() -> transport.complete(
                new GaModelTarget("CUSTOM", "llama", "custom-settings:1", "custom-key"),
                toolRequest(), Duration.ofSeconds(60), () -> true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("UNSUPPORTED_AGENT_MODEL");
        verifyNoInteractions(client);
    }

    @Test
    void providerRateLimitIsMappedToATypedFailure() throws Exception {
        when(custom.requireRuntimeSettings()).thenReturn(new RuntimeCustomSettings("CHAT_COMPLETIONS",
                "http://localhost:9/v1", "or-key", "model", 1));
        var transport = new ChatCompletionsGaTextTransport(custom, mapper,
                clientReturning(chatResponse(429, "{\"error\":{\"message\":\"rate\"}}"), new AtomicReference<>()));

        assertThatThrownBy(() -> transport.complete(
                new GaModelTarget("CUSTOM", "model", "custom-settings:1", "or-key"),
                textRequest(), Duration.ofSeconds(60), () -> true))
                .isInstanceOf(ModelProviderException.class);
    }
    @Test void exactQualifiedCustomTargetUsesNativeToolsAndRejectsRevisionDrift() throws Exception {
        var qualification=mock(com.specagent.model.contract.CustomNativeToolQualification.class);
        when(qualification.hasCustomTargets()).thenReturn(true);
        when(qualification.customQualified("http://localhost:9/v1","lite")).thenReturn(true);
        when(custom.requireRuntimeSettings()).thenReturn(new RuntimeCustomSettings("CHAT_COMPLETIONS","http://localhost:9/v1","fixture-key","lite",7));
        String nativeBody="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"native-one\",\"type\":\"function\",\"function\":{\"name\":\"project_search\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
        var captured=new AtomicReference<HttpRequest>();
        var client=clientReturning(chatResponse(200,nativeBody),captured);
        var transport=new ChatCompletionsGaTextTransport(custom,mapper,client,qualification);
        var target=new GaModelTarget("CUSTOM","lite","custom-settings:7","fixture-key");
        assertThat(transport.complete(target,toolRequest(),Duration.ofSeconds(20),()->true).toolCalls()).hasSize(1);
        when(custom.requireRuntimeSettings()).thenReturn(new RuntimeCustomSettings("CHAT_COMPLETIONS","http://localhost:9/v1","fixture-key","lite",8));
        assertThatThrownBy(()->transport.complete(target,toolRequest(),Duration.ofSeconds(20),()->true)).hasMessage("GA_MODEL_BINDING_MISMATCH");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).send(any(),any());
    }
    @Test void qualificationCannotAuthorizeAnotherEndpointOrProvider() {
        var qualification=mock(com.specagent.model.contract.CustomNativeToolQualification.class);
        when(qualification.hasCustomTargets()).thenReturn(true);
        when(qualification.customQualified("http://localhost:9/v1","lite")).thenReturn(true);
        when(custom.requireRuntimeSettings()).thenReturn(new RuntimeCustomSettings("CHAT_COMPLETIONS","http://localhost:10/v1","fixture-key","lite",7));
        var client=mock(HttpClient.class);
        var transport=new ChatCompletionsGaTextTransport(custom,mapper,client,qualification);
        assertThatThrownBy(()->transport.complete(new GaModelTarget("CUSTOM","lite","custom-settings:7","fixture-key"),toolRequest(),Duration.ofSeconds(20),()->true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->transport.complete(new GaModelTarget("CUSTOM","lite","custom-settings:7","fixture-key"),toolRequest(),Duration.ofSeconds(20),()->true)).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(client);
    }
}
