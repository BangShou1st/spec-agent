package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** True HTTP/SSE fixture tests for postSse. Local mock server only. */
class ProviderHttpSseTest {

    private static final String NL = String.valueOf((char) 10);
    private SseMockServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatCompletionsProtocolAdapter chat = new ChatCompletionsProtocolAdapter();
    private final ResponsesProtocolAdapter responses = new ResponsesProtocolAdapter();

    @BeforeEach void start() throws Exception {
        server = new SseMockServer();
    }

    @AfterEach void stop() {
        server.close();
    }

    private static String data(String j) {
        return "data: " + j + NL + NL;
    }

    private String chat(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("delta", Map.of("content", text)))));
    }

    private String call(ProtocolAdapter adapter, FragmentListener listener) {
        return ProviderHttpSupport.postSse(
                ProviderHttpSupport.newClient(Duration.ofSeconds(10)), mapper, server.url(),
                Map.of(), Map.of("model", "m", "stream", true), adapter, "test", listener);
    }

    @Test void multiEventStreamWithTerminalSucceeds() throws Exception {
        server.payload = data(chat("Hello")) + data(chat(" world")) + "data: [DONE]" + NL + NL;
        assertThat(call(chat, fragment -> true)).isEqualTo("Hello world");
    }

    @Test void eofWithoutTerminalFails() throws Exception {
        server.payload = data(chat("partial text")) + data(chat(" more"));
        assertThatThrownBy(() -> call(chat, fragment -> true))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("without protocol terminal");
    }

    @Test void cancelBeforeProseThrowsCancelled() throws Exception {
        server.payload = data(chat("Hello")) + "data: [DONE]" + NL + NL;
        assertThatThrownBy(() -> call(chat, fragment -> false))
                .isInstanceOf(StreamCancelledException.class);
    }

    @Test void cancelAfterNonVisibleEventsThrowsCancelled() throws Exception {
        String reasoning = mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("delta", Map.of("reasoning_content", "thinking...")))));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(data(reasoning));
        }
        sb.append("data: [DONE]").append(NL).append(NL);
        server.payload = sb.toString();
        server.chunkDelayMs = 5;
        AtomicInteger checkpoints = new AtomicInteger();
        assertThatThrownBy(() -> call(chat, fragment -> checkpoints.incrementAndGet() < 3))
                .isInstanceOf(StreamCancelledException.class);
        assertThat(checkpoints.get()).isGreaterThanOrEqualTo(3);
    }

    @Test void oversizedSingleEventFails() {
        String big = "x".repeat(ProviderHttpSupport.MAX_SSE_EVENT_BYTES + 1024);
        server.payload = data(big) + "data: [DONE]" + NL + NL;
        assertThatThrownBy(() -> call(chat, fragment -> true))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("budget");
    }

    @Test void oversizedAggregateFails() throws Exception {
        String chunk = "y".repeat(64 * 1024);
        String one = chat(chunk);
        StringBuilder sb = new StringBuilder();
        int events = ProviderHttpSupport.MAX_AGGREGATED_BYTES / chunk.length() + 2;
        for (int i = 0; i < events; i++) {
            sb.append(data(one));
        }
        sb.append("data: [DONE]").append(NL).append(NL);
        server.payload = sb.toString();
        assertThatThrownBy(() -> call(chat, fragment -> true))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("budget");
    }

    @Test void normalLongStreamPasses() throws Exception {
        String chunk = "z".repeat(1024);
        String one = chat(chunk);
        StringBuilder sb = new StringBuilder();
        StringBuilder expect = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(data(one));
            expect.append(chunk);
        }
        sb.append("data: [DONE]").append(NL).append(NL);
        server.payload = sb.toString();
        assertThat(call(chat, fragment -> true)).isEqualTo(expect.toString());
    }

    @Test void responsesCompletedSucceeds() throws Exception {
        String d = mapper.writeValueAsString(
                Map.of("type", "response.output_text.delta", "delta", "ok"));
        String done = mapper.writeValueAsString(Map.of("type", "response.completed"));
        server.payload = data(d) + data(done);
        assertThat(call(responses, fragment -> true)).isEqualTo("ok");
    }

    @Test void responsesIncompleteFails() throws Exception {
        String d = mapper.writeValueAsString(
                Map.of("type", "response.output_text.delta", "delta", "part"));
        String incomplete = mapper.writeValueAsString(Map.of("type", "response.incomplete"));
        server.payload = data(d) + data(incomplete);
        assertThatThrownBy(() -> call(responses, fragment -> true))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void responsesFailedFails() throws Exception {
        String failed = mapper.writeValueAsString(Map.of("type", "response.failed"));
        server.payload = data(failed);
        assertThatThrownBy(() -> call(responses, fragment -> true))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void chatStopSucceeds() throws Exception {
        String stop = mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))));
        server.payload = data(chat("Hello")) + data(stop);
        assertThat(call(chat, fragment -> true)).isEqualTo("Hello");
    }

    @Test void chatLengthAfterPartialDeltaFailsWithoutAuthoritativeFinal() throws Exception {
        String length = mapper.writeValueAsString(Map.of("choices",
                List.of(Map.of("delta", Map.of(), "finish_reason", "length"))));
        server.payload = data(chat("partial")) + data(length);
        assertThatThrownBy(() -> call(chat, fragment -> true))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void chatFailureFinishReasonsFail() throws Exception {
        for (String reason : new String[]{"content_filter", "tool_calls", "function_call"}) {
            String terminal = mapper.writeValueAsString(Map.of("choices",
                    List.of(Map.of("delta", Map.of(), "finish_reason", reason))));
            server.payload = data(chat("part")) + data(terminal);
            assertThatThrownBy(() -> call(chat, fragment -> true))
                    .isInstanceOf(ModelProviderException.class);
        }
    }

    @Test void chatDoneWithoutExplicitStopSucceeds() throws Exception {
        server.payload = data(chat("Hello")) + "data: [DONE]" + NL + NL;
        assertThat(call(chat, fragment -> true)).isEqualTo("Hello");
    }

    @Test void oversizedStreamingRequestFailsBeforeSend() {
        String huge = "x".repeat(ProviderHttpSupport.MAX_BODY_BYTES + 1024);
        Map<String, Object> body = Map.of("model", "m", "messages",
                List.of(Map.of("role", "user", "content", huge)));
        assertThatThrownBy(() -> ProviderHttpSupport.postSse(
                ProviderHttpSupport.newClient(Duration.ofSeconds(10)), mapper, server.url(),
                Map.of(), body, chat, "test", fragment -> true))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("too large");
    }
}
