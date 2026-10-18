package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.StreamCancelledException;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 文件名:KiloGaNativeTransportTest.java
 *
 * 测试目标:Kilo 原生 GA 传输的严格性——SSE 工具参数分片聚合、缺失终止信号/usage 的
 * 失败关闭、非法参数、重复工具身份、finish 不一致、未授权工具、取消,以及提供商错误
 * 的分类映射。全部离线:用一个 loopback SSE 桩替代上游。
 */
class KiloGaNativeTransportTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 启动 loopback SSE 桩;status/contentType/body 由调用方决定。 */
    private KiloGaNativeTransport transport(int status, String contentType, List<String> frames,
                                            AtomicInteger requests) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> respond(exchange, status, contentType, frames, requests));
        server.start();
        KiloGatewayProperties properties = new KiloGatewayProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setFreeOnly(false);
        return new KiloGaNativeTransport(properties);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, List<String> frames,
                                AtomicInteger requests) throws IOException {
        if (requests != null) requests.incrementAndGet();
        byte[] body;
        if (frames == null) {
            body = "{\"error\":{\"message\":\"provider error\"}}".getBytes(StandardCharsets.UTF_8);
        } else {
            StringBuilder text = new StringBuilder();
            // SSE 事件以空行分隔;缺空行会把多帧并成一个事件,从而在 EOF 处失败关闭。
            for (String frame : frames) text.append(frame).append("\n\n");
            body = text.toString().getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static GaModelContract.Request request(String toolChoice) {
        Map<String, Object> parameters = Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"));
        List<GaModelContract.Tool> tools = List.of(new GaModelContract.Tool(
                "project_search", "search projects", parameters));
        List<GaModelContract.Message> messages = List.of(
                new GaModelContract.Message("system", "call project_search once", null, null),
                new GaModelContract.Message("user", "search", null, null));
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(), messages, tools, toolChoice, 512, false);
    }

    private static GaModelTarget target() {
        return new GaModelTarget("KILO", "poolside/laguna-s-2.1:free", "kilo-settings:test", null);
    }

    private static List<String> fragmentedToolCall() {
        return List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"project_search\",\"arguments\":\"{\\\"qu\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"ery\\\":\\\"GA_NATIVE\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"_QUALIFICATION\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}",
                "data: [DONE]");
    }

    @Test
    void aggregatesFragmentedToolCallArgumentsAndRequiresTheTerminalDone() {
        AtomicInteger requests = new AtomicInteger();
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", fragmentedToolCall(), requests);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        GaModelContract.Response response = transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true);

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().name()).isEqualTo("project_search");
        assertThat(response.toolCalls().getFirst().arguments()).isEqualTo(Map.of("query", "GA_NATIVE_QUALIFICATION"));
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.usage().promptTokens()).isEqualTo(11);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void streamsCandidateTextButNeverReleasesToolArguments() {
        AtomicInteger requests = new AtomicInteger();
        List<String> frames = new ArrayList<>();
        frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
        frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}");
        frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}");
        frames.add("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        frames.add("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
        frames.add("data: [DONE]");
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, requests);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }
        List<String> fragments = new ArrayList<>();

        GaModelContract.Response response = transport.stream(target(), request("none"),
                Duration.ofSeconds(60), () -> true, text -> {
                    fragments.add(text);
                    return true;
                });

        assertThat(response.content()).isEqualTo("Hello");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(fragments).containsExactly("Hel", "lo");
    }

    @Test
    void aStreamWithoutTheTerminalDoneFailsClosed() {
        List<String> frames = new ArrayList<>(fragmentedToolCall());
        assertThat(frames.remove("data: [DONE]")).isTrue();
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(GaNativeStreamException.class);
    }

    @Test
    void missingUsageFailsClosed() {
        List<String> frames = new ArrayList<>(fragmentedToolCall());
        frames.removeIf(frame -> frame.contains("\"usage\""));
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(GaNativeStreamException.class);
    }

    @Test
    void malformedToolArgumentsFailClosed() {
        List<String> frames = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"project_search\",\"arguments\":\"not-json\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "data: [DONE]");
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(GaNativeStreamException.class);
    }

    @Test
    void duplicateToolIdentityFailsClosed() {
        List<String> frames = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"project_search\",\"arguments\":\"{\\\"query\\\":\\\"a\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"project_search\",\"arguments\":\"{\\\"query\\\":\\\"b\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "data: [DONE]");
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(GaNativeStreamException.class);
    }

    @Test
    void finishReasonInconsistentWithToolCallsFailsClosed() {
        List<String> frames = new ArrayList<>(fragmentedToolCall());
        frames.set(frames.size() - 3, "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(GaNativeStreamException.class);
    }

    @Test
    void unauthorizedToolNameIsRejectedByNativeValidation() {
        List<String> frames = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":\"{}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "data: [DONE]");
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", frames, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("required"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerErrorsAreClassifiedWithoutEchoingTheBody() {
        assertCategory(401, "text/event-stream", ModelGatewayErrorCategory.AUTHENTICATION);
        assertCategory(429, "text/event-stream", ModelGatewayErrorCategory.RATE_LIMITED);
        assertCategory(503, "text/event-stream", ModelGatewayErrorCategory.SERVER_ERROR);
        assertCategory(400, "text/event-stream", ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR);
    }

    private void assertCategory(int status, String contentType, ModelGatewayErrorCategory category) {
        KiloGaNativeTransport transport;
        try {
            transport = transport(status, contentType, null, null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }
        try {
            assertThatThrownBy(() -> transport.complete(target(), request("none"),
                    Duration.ofSeconds(60), () -> true))
                    .isInstanceOf(ModelProviderException.class)
                    .satisfies(error -> assertThat(((ModelProviderException) error).gatewayCategory())
                            .isEqualTo(category));
        } finally {
            stopServer();
        }
    }

    @Test
    void aNonSseSuccessResponseIsRejected() {
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "application/json", List.of("{}"), null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("none"),
                Duration.ofSeconds(60), () -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAlreadyInactiveExecutionNeverIssuesARequest() {
        KiloGaNativeTransport transport;
        try {
            transport = transport(200, "text/event-stream", List.of("data: [DONE]"), null);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }

        assertThatThrownBy(() -> transport.complete(target(), request("none"),
                Duration.ofSeconds(60), () -> false))
                .isInstanceOf(StreamCancelledException.class);
    }
}
