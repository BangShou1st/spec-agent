package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:ProviderStreamingCancellationTest.java
 *
 * 测试目标:验证三种协议适配器(Chat Completions / Responses / Anthropic)的
 * 流式"检查点"语义:reasoning、response.created、thinking 等不可见事件虽然不透出文本,
 * 仍会产生空片段检查点,使"文本出现前取消"依然可行。
 */
class ProviderStreamingCancellationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void chatReasoningPeriodStillCheckpoint() throws Exception {
        ChatCompletionsProtocolAdapter adapter = new ChatCompletionsProtocolAdapter();
        var reasoning = mapper.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}");
        // 没有可见文本,但 SSE 事件存在,传输层会以空片段做检查点(保证文本出现前可取消)。
        assertThat(adapter.extractVisibleText(reasoning, "c")).isNull();
        assertThat(adapter.isTerminalData("{}", reasoning, "c")).isFalse();
    }

    @Test void responsesLifecyclePeriodStillCheckpoint() throws Exception {
        ResponsesProtocolAdapter adapter = new ResponsesProtocolAdapter();
        var created = mapper.readTree("{\"type\":\"response.created\"}");
        assertThat(adapter.extractVisibleText(created, "r")).isNull();
        assertThat(adapter.isTerminalData("{}", created, "r")).isFalse();
    }

    @Test void anthropicThinkingPeriodStillCheckpoint() throws Exception {
        AnthropicMessagesProtocolAdapter adapter = new AnthropicMessagesProtocolAdapter();
        var start = mapper.readTree("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"thinking\"}}");
        assertThat(adapter.extractVisibleText(start, "a")).isNull();
        assertThat(adapter.isTerminalData("{}", start, "a")).isFalse();
    }
}
