package com.specagent.model.provider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.support.FakeModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** L2 protocol qualification with one common HTTP fixture. */
class ModelProtocolHarnessTest {
    CompatibilityProbeService probe() {
        return new CompatibilityProbeService(new ObjectMapper(),new ProtocolAdapterRegistry(List.of(
                new ChatCompletionsProtocolAdapter(),new ResponsesProtocolAdapter(),new AnthropicMessagesProtocolAdapter())),
                new CompatibilityProbeSemantics());
    }
    @ParameterizedTest @EnumSource(value=CustomApiFormat.class,names={"CHAT_COMPLETIONS","RESPONSES"})
    void chatAndStructuredUseTheChosenProtocolWithoutModelAllowList(CustomApiFormat format) throws Exception {
        try(var fake=new FakeModelProvider()) {
            probe().probeChat(format,fake.baseUrl(),"fixture-key","manual-unlisted");
            probe().probeCustom(format,fake.baseUrl(),"fixture-key","manual-unlisted");
            assertThat(fake.requests).hasSize(2);
            assertThat(fake.requests).allSatisfy(r->assertThat(r.path("model").asText()).isEqualTo("manual-unlisted"));
        }
    }
    @Test void nativeRoundTripPreservesCallIdentityAndOpaqueSignature() throws Exception {
        try(var fake=new FakeModelProvider()) {
            probe().probeNativeTools(CustomApiFormat.CHAT_COMPLETIONS,fake.baseUrl(),"fixture-key","new-model");
            assertThat(fake.requests).hasSize(2);
            // 资格验证走生产传输:非流式请求形状显式 stream:false,与 GA 执行一致。
            assertThat(fake.requests).allSatisfy(r->assertThat(r.path("stream").asBoolean()).isFalse());
            var history=fake.requests.get(1).path("messages");
            assertThat(history.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("fixture-call");
            assertThat(history.get(2).path("tool_call_id").asText()).isEqualTo("fixture-call");
            assertThat(history.get(1).path("tool_calls").get(0).path("extra_content").path("google")
                    .path("thought_signature").asText()).isEqualTo("fixture-signature");
        }
    }
    @Test void responsesNativeRoundTripQualifiesOnlyAfterTwoProtocolRequests() throws Exception {
        try(var fake=new com.specagent.support.FakeResponsesProvider()) {
            probe().probeNativeTools(CustomApiFormat.RESPONSES,fake.baseUrl(),"fixture-key","responses-model");
            assertThat(fake.requests).hasSize(2);
            assertThat(fake.requests).allSatisfy(r->assertThat(r.path("model").asText()).isEqualTo("responses-model"));
            assertThat(fake.requests).allSatisfy(r->assertThat(r.path("store").asBoolean()).isFalse());
            var first=fake.requests.get(0);
            assertThat(first.path("input").get(0).path("role").asText()).isEqualTo("user");
            assertThat(first.path("tools").get(0).path("name").asText()).isEqualTo("probe_echo");
            assertThat(first.path("tool_choice").asText()).isEqualTo("required");
            var second=fake.requests.get(1);
            var historyCall=second.path("input").get(1);
            assertThat(historyCall.path("type").asText()).isEqualTo("function_call");
            assertThat(historyCall.path("call_id").asText()).isEqualTo("call_1_0");
            var output=second.path("input").get(2);
            assertThat(output.path("type").asText()).isEqualTo("function_call_output");
            assertThat(output.path("call_id").asText()).isEqualTo("call_1_0");
        }
    }
    @Test void responsesWrongToolFailsBeforeFollowup() throws Exception {
        try(var fake=new com.specagent.support.FakeResponsesProvider()) {
            fake.wrongToolName=true;
            assertThatThrownBy(()->probe().probeNativeTools(
                    CustomApiFormat.RESPONSES,fake.baseUrl(),null,"model"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(fake.requests).hasSize(1);
        }
    }
    /**
     * MS-CROSS-R1(R1-B):native probe 必须真实经过三种生产原生传输。
     * {@code stream:false} 之类 HTTP 形状断言无法区分旁路实现(旧旁路的
     * {@link GaChatCompletionsAdapter#requestBody} 同样生成 stream:false),
     * 因此通过包私有传输工厂 seam 观察实际创建的传输类型:每个协议只调用
     * 自己的生产传输工厂,绝不调用其他传输,也绝不绕过工厂直接 HTTP。
     * 每个协议同时保持完整的双轮 probe_echo 闭环断言(由被 spy 的真实方法执行)。
     */
    @Test void nativeProbeRoutesThroughTheProductionTransports() throws Exception {
        try (var fake = new FakeModelProvider()) {
            var probe = spy(probe());
            probe.probeNativeTools(CustomApiFormat.CHAT_COMPLETIONS, fake.baseUrl(), "fixture-key", "m");
            assertThat(fake.requests).hasSize(2);
            // 双轮(工具调用轮 + 终答轮)都必须经生产传输工厂创建传输。
            verify(probe, times(2)).newChatCompletionsTransport(any());
            verify(probe, never()).newResponsesTransport(any());
            verify(probe, never()).newAnthropicTransport(any());
        }
        try (var fake = new com.specagent.support.FakeResponsesProvider()) {
            var probe = spy(probe());
            probe.probeNativeTools(CustomApiFormat.RESPONSES, fake.baseUrl(), "fixture-key", "m");
            assertThat(fake.requests).hasSize(2);
            verify(probe, times(2)).newResponsesTransport(any());
            verify(probe, never()).newChatCompletionsTransport(any());
            verify(probe, never()).newAnthropicTransport(any());
        }
        try (var fake = new com.specagent.support.FakeAnthropicMessagesProvider()) {
            fake.toolShape = com.specagent.support.FakeAnthropicMessagesProvider.ToolShape.SINGLE;
            var probe = spy(probe());
            probe.probeNativeTools(CustomApiFormat.ANTHROPIC_MESSAGES, fake.baseUrl(), "fixture-key", "m");
            assertThat(fake.requests).hasSize(2);
            verify(probe, times(2)).newAnthropicTransport(any());
            verify(probe, never()).newChatCompletionsTransport(any());
            verify(probe, never()).newResponsesTransport(any());
            // Anthropic 身份头经生产传输原样到达。
            assertThat(fake.apiKeys).containsOnly("fixture-key");
        }
    }

    @Test void wrongToolStopsBeforeFollowup() throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.badCall=true;
            assertThatThrownBy(()->probe().probeNativeTools(
                    CustomApiFormat.CHAT_COMPLETIONS,fake.baseUrl(),null,"model"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(fake.requests).hasSize(1);
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={400,429,500})
    void errorsAreTypedWithNoRetryOrCredentialLeak(int status) throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.status=status;
            assertThatThrownBy(()->probe().probeChat(CustomApiFormat.CHAT_COMPLETIONS,fake.baseUrl(),"fixture-secret","model"))
                    .isInstanceOf(ModelProviderException.class).hasMessageNotContaining("fixture-secret");
            assertThat(fake.requests).hasSize(1);
        }
    }
    @Test void malformedResponseFailsClosed() throws Exception {
        try(var fake=new FakeModelProvider()) {
            fake.malformed=true;
            assertThatThrownBy(()->probe().probeChat(CustomApiFormat.CHAT_COMPLETIONS,fake.baseUrl(),null,"model"))
                    .isInstanceOf(ModelProviderException.class);
        }
    }
}
