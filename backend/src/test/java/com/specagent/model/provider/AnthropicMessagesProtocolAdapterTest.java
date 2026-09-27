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
 * 流式增量只透出 text_delta(text/thinking/tool/ping 区分)、message_stop 为终止事件。
 */
class AnthropicMessagesProtocolAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessagesProtocolAdapter adapter = new AnthropicMessagesProtocolAdapter();

    @Test void buildsRealAnthropicPayload() {
        var req = new ModelInferenceRequest(UUID.randomUUID(), "p",
                List.of(new ModelInferenceMessage("user", "hi")), 256, ModelOutputContract.text());
        var body = adapter.buildRequestBody(req, "m");
        assertThat(body.get("model")).isEqualTo("m");
        assertThat(body).containsKey("max_tokens");
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).doesNotContainKey("choices");
        assertThat(body).doesNotContainKey("input");
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
}
