package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Success terminals gate success; failure terminals never pass as success. */
class TerminalSemanticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatCompletionsProtocolAdapter chat = new ChatCompletionsProtocolAdapter();
    private final ResponsesProtocolAdapter responses = new ResponsesProtocolAdapter();
    private final AnthropicMessagesProtocolAdapter anthropic = new AnthropicMessagesProtocolAdapter();

    private static ObjectNode typed(String type) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("type", type);
        return o;
    }

    @Test void chatDoneIsSuccessErrorIsFailure() {
        assertThat(chat.isSuccessfulTerminal("[DONE]", null, "c")).isTrue();
        ObjectNode err = MAPPER.createObjectNode();
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("message", "x");
        err.set("error", inner);
        assertThat(chat.isFailureTerminal(err, "c")).isTrue();
        ObjectNode plain = MAPPER.createObjectNode();
        assertThat(chat.isSuccessfulTerminal("{}", plain, "c")).isFalse();
    }

    @Test void chatFinishReasonSemantics() throws Exception {
        var stop = MAPPER.readTree("{\"choices\":[{\"finish_reason\":\"stop\"}]}");
        assertThat(chat.isTerminalData("{}", stop, "c")).isTrue();
        assertThat(chat.isSuccessfulTerminal("{}", stop, "c")).isTrue();
        assertThat(chat.isFailureTerminal(stop, "c")).isFalse();
        for (String reason : new String[]{"length", "content_filter", "tool_calls", "function_call"}) {
            var data = MAPPER.readTree("{\"choices\":[{\"finish_reason\":\"" + reason + "\"}]}");
            assertThat(chat.isTerminalData("{}", data, "c")).isTrue();
            assertThat(chat.isSuccessfulTerminal("{}", data, "c")).isFalse();
            assertThat(chat.isFailureTerminal(data, "c")).isTrue();
        }
    }

    @Test void responsesCompletedOnlySuccess() {
        assertThat(responses.isSuccessfulTerminal("{}", typed("response.completed"), "r")).isTrue();
        assertThat(responses.isFailureTerminal(typed("response.failed"), "r")).isTrue();
        assertThat(responses.isFailureTerminal(typed("response.incomplete"), "r")).isTrue();
        assertThat(responses.isFailureTerminal(typed("error"), "r")).isTrue();
        assertThat(responses.isSuccessfulTerminal("{}", typed("response.incomplete"), "r")).isFalse();
    }

    @Test void anthropicStopSuccessErrorFailure() {
        assertThat(anthropic.isSuccessfulTerminal("{}", typed("message_stop"), "a")).isTrue();
        assertThat(anthropic.isFailureTerminal(typed("error"), "a")).isTrue();
        assertThat(anthropic.isSuccessfulTerminal("{}", typed("ping"), "a")).isFalse();
    }
}
