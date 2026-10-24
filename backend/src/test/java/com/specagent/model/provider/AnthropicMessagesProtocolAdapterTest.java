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
 * 文件名:AnthropicMessagesProtocolAdapterTest.java
 *
 * 测试目标:验证 Anthropic Messages 协议适配器的报文构造与解析:
 * 构建真实的 Anthropic 请求体(不含 response_format/choices/input)、
 * 使用 x-api-key 与版本号做鉴权、非流式响应只取 text 块、
 * 流式增量只透出 text_delta(text/thinking/tool/ping 区分)、message_stop 为终止事件,
 * 以及 MS-C3 的 JSON_OBJECT 宿主 Envelope 桥接(请求构造 + 严格解包)。
 */
class AnthropicMessagesProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessagesProtocolAdapter adapter = new AnthropicMessagesProtocolAdapter();

    private ModelInferenceRequest textRequest() {
        return new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("user", "hi")), 256, ModelOutputContract.text());
    }

    private ModelInferenceRequest jsonRequest(String system, String user) {
        return new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("system", system),
                        new ModelInferenceMessage("user", user)), 256,
                ModelOutputContract.jsonObject());
    }

    @Test void buildsRealAnthropicPayload() {
        var body = adapter.buildRequestBody(textRequest(), "m");
        assertThat(body.get("model")).isEqualTo("m");
        assertThat(body).containsKey("max_tokens");
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).doesNotContainKey("choices");
        assertThat(body).doesNotContainKey("input");
        assertThat(body).doesNotContainKey("output_config");
    }

    @Test void authUsesXApiKeyAndVersion() {
        var h = adapter.authHeaders("k");
        assertThat(h.get("x-api-key")).isEqualTo("k");
        assertThat(h.get("anthropic-version")).isEqualTo(AnthropicMessagesProtocolAdapter.ANTHROPIC_VERSION);
        assertThat(adapter.authHeaders(null)).containsKey("anthropic-version");
    }

    @Test void normalTextBlockOnly() throws Exception {
        var root = mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"thinking\",\"thinking\":\"secret\"}],\"stop_reason\":\"end_turn\"}");
        assertThat(adapter.parseNonStreamResponse(root, "a").content()).isEqualTo("hello");
    }

    @Test void onlyTextDeltaSurfaces() throws Exception {
        var good = mapper.readTree("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}");
        assertThat(adapter.extractVisibleText(good, "a")).isEqualTo("Hi");
        var thinking = mapper.readTree("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"s\"}}");
        assertThat(adapter.extractVisibleText(thinking, "a")).isNull();
        var tool = mapper.readTree("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"x\"}}");
        assertThat(adapter.extractVisibleText(tool, "a")).isNull();
        var ping = mapper.readTree("{\"type\":\"ping\"}");
        assertThat(adapter.extractVisibleText(ping, "a")).isNull();
    }

    @Test void messageStopTerminal() throws Exception {
        var stop = mapper.readTree("{\"type\":\"message_stop\"}");
        assertThat(adapter.isTerminalData("{}", stop, "a")).isTrue();
    }

    // ---- MS-C3 JSON_OBJECT Envelope bridge -------------------------------------

    @Test void jsonObjectRequestUsesOutputConfigFormatWithEnvelopeSchema() {
        var body = adapter.buildRequestBody(jsonRequest("be precise", "decide"), "m");
        assertThat(body).containsKey("output_config");
        assertThat(body.get("output_config")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var outputConfig = (Map<String, Object>) body.get("output_config");
        @SuppressWarnings("unchecked")
        var format = (Map<String, Object>) outputConfig.get("format");
        assertThat(format.get("type")).isEqualTo("json_schema");
        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) format.get("schema");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(schema.get("required")).isEqualTo(List.of("json"));
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).doesNotContainKey("output_format");
        // Envelope 指令进入顶层 system,不污染消息历史。
        assertThat((String) body.get("system")).contains("be precise").contains("\"json\"");
    }

    @Test void envelopeUnwrapReturnsInnerObjectOnly() throws Exception {
        String inner = "{\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"}";
        var envelope = mapper.createObjectNode();
        envelope.put("json", inner);
        var content = mapper.createArrayNode();
        var textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", mapper.writeValueAsString(envelope));
        var root = mapper.createObjectNode();
        root.set("content", content);
        root.put("stop_reason", "end_turn");
        var response = adapter.parseNonStreamResponse(root, "a", ModelOutputContract.jsonObject());
        assertThat(response.content()).isEqualTo(inner);
        assertThat(response.finishReason()).isEqualTo("end_turn");
        // 外层包装绝不作为决策输出出现。
        assertThat(response.content()).doesNotStartWith("{\"json\"");
    }

    @Test void envelopeVariantsAllFailClosed() throws Exception {
        String[][] cases = {
                {"missing json field", "{\"other\":\"value\"}"},
                {"extra field", "{\"json\":\"{}\",\"extra\":1}"},
                {"inner not object", "{\"json\":\"[1,2]\"}"},
                {"inner string", "{\"json\":\"\\\"text\\\"\"}"},
                {"inner null", "{\"json\":\"null\"}"},
                {"inner malformed", "{\"json\":\"{not json\"}"},
                {"duplicate inner key", "{\"json\":\"{\\\"k\\\":1,\\\"k\\\":2}\"}"},
                {"invalid unicode", "{\"json\":\"\\ud800\"}"},
                {"trailing tokens", "{\"json\":\"{}\"} trailing"},
                {"raw json without envelope", "{\"kind\":\"FINAL\"}"},
        };
        for (String[] item : cases) {
            String text = item[1];
            String raw = mapper.writeValueAsString(text);
            var root = mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":" + raw +
                    "}],\"stop_reason\":\"end_turn\"}");
            assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "a", ModelOutputContract.jsonObject()))
                    .as(item[0])
                    .isInstanceOf(ModelProviderException.class);
        }
    }

    @Test void envelopeRejectsNonCompletedStopReasonsAndForeignBlocks() throws Exception {
        for (String stop : new String[]{"refusal", "max_tokens", "pause_turn", "tool_use", "mcp_request"}) {
            var root = mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"json\\\":\\\"{}\\\"}\"}],\"stop_reason\":\"" + stop + "\"}");
            assertThatThrownBy(() -> adapter.parseNonStreamResponse(root, "a", ModelOutputContract.jsonObject()))
                    .as("stop_reason " + stop)
                    .isInstanceOf(ModelProviderException.class);
        }
        // 非文本内容块在 JSON 桥接中被拒绝(不允许 tool_use 混入结构化输出)。
        var toolBlock = mapper.readTree("{\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"n\",\"input\":{}}],\"stop_reason\":\"end_turn\"}");
        assertThatThrownBy(() -> adapter.parseNonStreamResponse(toolBlock, "a", ModelOutputContract.jsonObject()))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void thinkingOnlyResponseStillYieldsTextForTextContract() throws Exception {
        // thinking 块丢弃,end_turn + text 是合法终态(与既有 parse 语义一致)。
        var root = mapper.readTree("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"s\"},{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}");
        assertThat(adapter.parseNonStreamResponse(root, "a", ModelOutputContract.text()).content()).isEqualTo("ok");
    }

    @Test void jsonContractDoesNotLeakEnvelopeThroughStreaming() {
        assertThatThrownBy(() -> adapter.buildStreamRequestBody(jsonRequest("s", "u"), "m"))
                .isInstanceOf(ModelProviderException.class);
    }
}
