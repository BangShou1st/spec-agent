package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 文件名:RoutingGaNativeInferenceGatewayTest.java
 *
 * 测试目标:GA 原生执行按<b>冻结目标里的提供商</b>路由——Zen 与 Kilo 走各自经审计的
 * 原生传输(同时承载 TEXT_CHAT / NATIVE_TOOLS),OpenRouter 与 Custom 走纯文本
 * Chat Completions 传输,而未知编码一律失败关闭,绝不退回文本模拟或悄悄换提供商。
 */
class RoutingGaNativeInferenceGatewayTest {

    private final OpenCodeZenTransport zen = mock(OpenCodeZenTransport.class);
    private final KiloGaNativeTransport kilo = mock(KiloGaNativeTransport.class);
    private final ChatCompletionsGaTextTransport textChat = mock(ChatCompletionsGaTextTransport.class);
    private final ResponsesGaNativeTransport responses = mock(ResponsesGaNativeTransport.class);
    private RuntimeCustomSettings customRuntime = customSettings("CHAT_COMPLETIONS", "chat-model");
    private CustomRuntimeSettingsPort customSettings = customSettings(customRuntime);
    private RoutingGaNativeInferenceGateway gateway =
            new RoutingGaNativeInferenceGateway(zen, kilo, textChat, responses, customSettings);

    private void customFormat(String format, String model) {
        customRuntime = customSettings(format, model);
        customSettings = customSettings(customRuntime);
        gateway = new RoutingGaNativeInferenceGateway(zen, kilo, textChat, responses, customSettings);
    }

    private static GaModelContract.Request request() {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "hello", null, null)),
                List.of(), "none", 128, false);
    }

    private static GaModelTarget target(String provider, String model) {
        return new GaModelTarget(provider, model, "revision:" + provider, "super-secret-key");
    }

    private GaModelTarget customTarget(String model) {
        return new GaModelTarget("CUSTOM", model, customRuntime.bindingRevision(), "super-secret-key");
    }

    private static RuntimeCustomSettings customSettings(String format, String model) {
        return new RuntimeCustomSettings(format, "http://localhost:9/v1", "super-secret-key", model, 1,
                UUID.randomUUID(), true);
    }

    private CustomRuntimeSettingsPort customSettings(RuntimeCustomSettings settings) {
        return new CustomRuntimeSettingsPort() {
            @Override
            public RuntimeCustomSettings requireRuntimeSettings() {
                return settings;
            }

            @Override
            public RuntimeCustomSettings requireRuntimeSettings(String frozenRevision) {
                if (!settings.bindingRevision().equals(frozenRevision)) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                return settings;
            }
        };
    }

    private static GaModelContract.Response response() {
        return new GaModelContract.Response(GaModelContract.VERSION, "ok", List.of(), "stop",
                new GaModelContract.Usage(1, 1));
    }

    @Test
    void openCodeZenUsesTheVerifiedNativeTransport() {
        when(zen.streamNativeGa(any(), anyString(), any(), eq("gpt-5.6-terra"), any(), any(), any()))
                .thenReturn(response());
        when(zen.completeNativeGa(any(), anyString(), any(), eq("gpt-5.6-terra"), any(), any()))
                .thenReturn(response());
        GaModelContract.Request request = request();
        GaModelTarget target = target("OPENCODE_ZEN", "gpt-5.6-terra");

        gateway.stream(target, UUID.randomUUID(), request, Duration.ofSeconds(30), () -> true, text -> true);
        gateway.complete(target, UUID.randomUUID(), request, Duration.ofSeconds(30), () -> true);

        // 非流式入口保持 Zen 已验证的原生调用形态,绝不被伪装成 stream。
        verify(zen).streamNativeGa(any(), anyString(), any(), eq("gpt-5.6-terra"), any(), any(), any());
        verify(zen).completeNativeGa(any(), anyString(), any(), eq("gpt-5.6-terra"), any(), any());
        verifyNoInteractions(kilo);
    }

    @Test
    void kiloUsesItsOwnNativeTransport() {
        when(kilo.complete(any(), any(), any(), any())).thenReturn(response());
        GaModelTarget target = target("KILO", "poolside/laguna-s-2.1:free");

        gateway.complete(target, UUID.randomUUID(), request(), Duration.ofSeconds(30), () -> true);

        verify(kilo).complete(eq(target), any(), any(), any());
        verifyNoInteractions(zen);
    }

    @Test
    void streamingFollowsTheSameFrozenProviderRouting() {
        when(kilo.stream(any(), any(), any(), any(), any())).thenReturn(response());
        GaModelTarget target = target("KILO", "cohere/north-mini-code:free");

        gateway.stream(target, UUID.randomUUID(), request(), Duration.ofSeconds(30), () -> true, text -> true);

        verify(kilo).stream(eq(target), any(), any(), any(), any());
        verifyNoInteractions(zen);
    }

    @Test
    void customUsesTheChatCompletionsTransport() {
        when(textChat.complete(any(), any(), any(), any())).thenReturn(response());
        when(textChat.stream(any(), any(), any(), any(), any())).thenReturn(response());

        gateway.complete(customTarget("chat-model"), UUID.randomUUID(), request(),
                Duration.ofSeconds(30), () -> true);
        gateway.stream(customTarget("chat-model"), UUID.randomUUID(), request(),
                Duration.ofSeconds(30), () -> true, text -> true);

        verify(textChat).complete(any(), any(), any(), any());
        verify(textChat).stream(any(), any(), any(), any(), any());
        verifyNoInteractions(zen, kilo, responses);
    }

    @Test
    void customResponsesUsesTheResponsesTransportWithoutChatFallback() {
        customFormat("RESPONSES", "responses-model");
        when(responses.complete(any(), any(), any(), any())).thenReturn(response());
        when(responses.stream(any(), any(), any(), any(), any())).thenReturn(response());

        gateway.complete(customTarget("responses-model"), UUID.randomUUID(), request(),
                Duration.ofSeconds(30), () -> true);
        gateway.stream(customTarget("responses-model"), UUID.randomUUID(), request(),
                Duration.ofSeconds(30), () -> true, text -> true);

        verify(responses).complete(any(), any(), any(), any());
        verify(responses).stream(any(), any(), any(), any(), any());
        verifyNoInteractions(zen, kilo, textChat);
    }

    @Test
    void customAnthropicProtocolFailsClosedWithoutFallback() {
        customFormat("ANTHROPIC_MESSAGES", "anthropic-model");

        assertThatThrownBy(() -> gateway.complete(customTarget("anthropic-model"), UUID.randomUUID(),
                request(), Duration.ofSeconds(30), () -> true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("UNSUPPORTED_AGENT_MODEL");
        verifyNoInteractions(zen, kilo, textChat, responses);
    }

    @Test
    void customResponsesErrorsNeverFallBackToChatCompletions() {
        customFormat("RESPONSES", "frozen-model");
        when(responses.complete(any(), any(), any(), any())).thenThrow(
                ModelProviderException.rateLimited("custom-responses", "rate limited"));

        assertThatThrownBy(() -> gateway.complete(customTarget("frozen-model"), UUID.randomUUID(),
                request(), Duration.ofSeconds(30), () -> true))
                .isInstanceOf(ModelProviderException.class);
        verifyNoInteractions(textChat);
    }

    @Test
    void unknownProviderCodeFailsClosed() {
        assertThatThrownBy(() -> gateway.complete(target("NOT_A_PROVIDER", "any-model"), UUID.randomUUID(),
                request(), Duration.ofSeconds(30), () -> true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("UNSUPPORTED_AGENT_MODEL");
        verifyNoInteractions(zen, kilo, textChat, responses);
    }

    @Test
    void aProviderErrorPropagatesWithoutFallbackToAnotherProvider() {
        ModelProviderException failure = ModelProviderException.rateLimited("kilo", "rate limited");
        when(kilo.stream(any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> gateway.stream(target("KILO", "poolside/laguna-s-2.1:free"),
                UUID.randomUUID(), request(), Duration.ofSeconds(30), () -> true, text -> true))
                .isSameAs(failure);
        verifyNoInteractions(zen);
    }

    @Test
    void theRoutedModelIsExactlyTheFrozenOne() {
        when(kilo.stream(any(), any(), any(), any(), any())).thenReturn(response());

        gateway.stream(target("KILO", "frozen-model"), UUID.randomUUID(), request(),
                Duration.ofSeconds(30), () -> true, text -> true);

        var captor = org.mockito.ArgumentCaptor.forClass(GaModelTarget.class);
        verify(kilo).stream(captor.capture(), any(), any(), any(), any());
        assertThat(captor.getValue().model()).isEqualTo("frozen-model");
        // 目标绝不把凭据原文泄露到诊断字符串里。
        assertThat(captor.getValue().toString()).doesNotContain("super-secret-key");
        assertThat(captor.getValue().toString()).contains("<present>");
        assertThat(Map.of("provider", captor.getValue().provider()))
                .containsEntry("provider", "KILO");
    }
}
