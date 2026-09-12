package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderStreamingCancellationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void chatReasoningPeriodStillCheckpoint() throws Exception {
        ChatCompletionsProtocolAdapter adapter = new ChatCompletionsProtocolAdapter();
        var reasoning = mapper.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}");
        // No visible prose, but the SSE event exists so the transport will
        // checkpoint via an empty fragment (cancel before text works).
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
