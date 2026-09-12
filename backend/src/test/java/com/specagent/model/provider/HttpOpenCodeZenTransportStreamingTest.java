package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire-level streaming: fragments surface pre-completion; decline aborts. */
class HttpOpenCodeZenTransportStreamingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private volatile String sseBody = "";

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                int at = 0;
                while (at < body.length) {
                    int end = Math.min(at + 64, body.length);
                    out.write(body, at, end - at);
                    out.flush();
                    at = end;
                    try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpOpenCodeZenTransport transport() {
        return new HttpOpenCodeZenTransport(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), 5, "DIRECT");
    }

    private OpenCodeChatCompletionRequest request() {
        return new OpenCodeChatCompletionRequest("mimo-v2.5-free",
                List.of(new OpenCodeChatMessage("system", "system contract"),
                        new OpenCodeChatMessage("user", "user context")));
    }

    private static String frame(String innerJson) {
        return "data: " + innerJson + "\n\n";
    }

    private static String choiceFrame(String text) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    private static String doneFrame() {
        return "data: [DONE]\n\n";
    }

    @Test
    void fragmentsArriveBeforeCompletion() {
        sseBody = frame(choiceFrame("A")) + frame(choiceFrame("B")) + frame(choiceFrame("C")) + doneFrame();
        List<String> seen = new CopyOnWriteArrayList<>();
        List<Long> nanos = new CopyOnWriteArrayList<>();
        OpenCodeCompletionResponse response = transport().completeStreaming("k", "ses_testsession02", request(), fragment -> {
            seen.add(fragment);
            nanos.add(System.nanoTime());
            return true;
        });
        long returnedAt = System.nanoTime();
        assertThat(seen).containsExactly("A", "B", "C");
        assertThat(nanos.get(0)).isLessThan(returnedAt);
        assertThat(response.content()).isEqualTo("ABC");
    }

    @Test
    void declinedFragmentAbortsStream() {
        sseBody = frame(choiceFrame("A")) + frame(choiceFrame("B")) + doneFrame();
        AtomicBoolean first = new AtomicBoolean(false);
        assertThatThrownBy(() -> transport().completeStreaming("k", "ses_testsession02", request(), fragment -> {
            if (first.compareAndSet(false, true)) return true;
            return false;
        })).isInstanceOf(StreamCancelledException.class);
    }

    private static String reasoningFrame(String text) {
        return "{\"choices\":[{\"delta\":{\"reasoning_content\":\"" + text + "\"}}]}";
    }

    @Test
    void reasoningOnlyEventsOfferCancellationCheckpoints() {
        sseBody = frame(reasoningFrame("thinking")) + frame(choiceFrame("Hi")) + doneFrame();
        List<String> seen = new CopyOnWriteArrayList<>();
        OpenCodeCompletionResponse response = transport().completeStreaming("k", "ses_testsession03", request(), fragment -> {
            seen.add(fragment);
            return true;
        });
        assertThat(seen).containsExactly("", "Hi");
        assertThat(response.content()).isEqualTo("Hi");
    }

    @Test
    void declineDuringReasoningAbortsBeforeContent() {
        sseBody = frame(reasoningFrame("thinking")) + frame(choiceFrame("Hi")) + doneFrame();
        assertThatThrownBy(() -> transport().completeStreaming("k", "ses_testsession04", request(), fragment -> {
            return !fragment.isEmpty();
        })).isInstanceOf(StreamCancelledException.class);
    }
}
