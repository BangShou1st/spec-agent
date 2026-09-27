package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:ChatCompletionsProtocolAdapterTest.java
 *
 * 测试目标:验证 OpenAI Chat Completions 协议适配器:文本/JSON Object 请求体的
 * response_format 映射、可选的 Bearer 鉴权头、非流式响应与流式增量的解析
 * (reasoning/tool_calls/usage 不作为可见文本)、[DONE] 与 finish_reason 的终止语义
 * (只有 stop 算成功终止,其余按失败处理)、缺失 finish_reason 时 fail-closed,
 * 以及 HTTP 状态码到网关错误类别的映射。
 */
class ChatCompletionsProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatCompletionsProtocolAdapter adapter = new ChatCompletionsProtocolAdapter();

    private ModelInferenceRequest req(ModelOutputContract contract) {
        return new ModelInferenceRequest(UUID.randomUUID(), "probe",
                List.of(new ModelInferenceMessage("user", "hi")), 256, contract);
    }

    @Test void requestMappingTextHasNoFormat() {
        var body = adapter.buildRequestBody(req(ModelOutputContract.text()), "m");
        assertThat(body).containsEntry("model", "m");
        assertThat(body).doesNotContainKey("response_format");
    }

    @Test void requestMappingJsonObject() {
        var body = adapter.buildRequestBody(req(ModelOutputContract.jsonObject()), "m");
        Map<?, ?> rf = (Map<?, ?>) body.get("response_format");
        assertThat(rf.get("type")).isEqualTo("json_object");
    }

    @Test void authOptional() {
        assertThat(adapter.authHeaders(null)).isEmpty();
        assertThat(adapter.authHeaders("  ")).isEmpty();
        assertThat(adapter.authHeaders("k").get("Authorization")).isEqualTo("Bearer k");
    }

    @Test void normalResponse() throws Exception {
        var root = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}");
        var res = adapter.parseNonStreamResponse(root, "chat");
        assertThat(res.content()).isEqualTo("hello");
        assertThat(res.promptTokens()).isEqualTo(1);
    }

    @Test void streamTextDelta() throws Exception {
        var data = mapper.readTree("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}");
        assertThat(adapter.extractVisibleText(data, "chat")).isEqualTo("Hi");
    }

    @Test void reasoningAndUsageIgnored() throws Exception {
        var reasoning = mapper.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"secret\"}}]}");
        assertThat(adapter.extractVisibleText(reasoning, "chat")).isNull();
        var tool = mapper.readTree("{\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}");
        assertThat(adapter.extractVisibleText(tool, "chat")).isNull();
        var usage = mapper.readTree("{\"usage\":{\"prompt_tokens\":5}}");
        assertThat(adapter.extractVisibleText(usage, "chat")).isNull();
    }

    @Test void doneIsTerminal() throws Exception {
        assertThat(adapter.isTerminalData("[DONE]", null, "chat")).isTrue();
        var data = mapper.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertThat(adapter.isTerminalData("{\"x\":1}", data, "chat")).isTrue();
    }

    @Test void stopIsOnlySuccessTerminal() throws Exception {
        var stop = mapper.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertThat(adapter.isSuccessfulTerminal("{}", stop, "chat")).isTrue();
        assertThat(adapter.isFailureTerminal(stop, "chat")).isFalse();
        for (String reason : new String[]{"length", "content_filter", "tool_calls", "function_call"}) {
            var data = mapper.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}");
            assertThat(adapter.isTerminalData("{}", data, "chat")).isTrue();
            assertThat(adapter.isSuccessfulTerminal("{}", data, "chat")).isFalse();
            assertThat(adapter.isFailureTerminal(data, "chat")).isTrue();
        }
        // 未出现失败 finish_reason 时,不带显式 stop 的 [DONE] 仍视为成功终止。
        assertThat(adapter.isSuccessfulTerminal("[DONE]", null, "chat")).isTrue();
        var plain = mapper.readTree("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}");
        assertThat(adapter.isSuccessfulTerminal("{}", plain, "chat")).isFalse();
        assertThat(adapter.isFailureTerminal(plain, "chat")).isFalse();
    }

    @Test void nonStreamOnlyStopPasses() throws Exception {
        var stop = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}");
        assertThat(adapter.parseNonStreamResponse(stop, "chat").content()).isEqualTo("hello");
        for (String reason : new String[]{"length", "content_filter", "tool_calls", "function_call"}) {
            var root = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"" + reason + "\"}]}");
            assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "chat"))
                    .isInstanceOf(ModelProviderException.class);
        }
    }

    @Test void nonStreamMissingFinishReasonFailsClosed() throws Exception {
        var missing = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(missing, "chat"))
                .isInstanceOf(ModelProviderException.class);
        var nul = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":null}]}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(nul, "chat"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void errorMapping() {
        assertThat(adapter.mapHttpError(401, "", "c").gatewayCategory().name()).isEqualTo("AUTHENTICATION");
        assertThat(adapter.mapHttpError(429, "", "c").gatewayCategory().name()).isEqualTo("RATE_LIMITED");
        assertThat(adapter.mapHttpError(404, "", "c").gatewayCategory().name()).isEqualTo("INVALID_MODEL");
        assertThat(adapter.mapHttpError(500, "", "c").gatewayCategory().name()).isEqualTo("SERVER_ERROR");
    }
}
