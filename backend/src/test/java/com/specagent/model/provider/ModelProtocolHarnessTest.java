package com.specagent.model.provider;
import static org.assertj.core.api.Assertions.*;
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
