package com.specagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic OpenAI Responses fixture: emits function_call items with
 * call_id, accepts function_call_output replay, and can be switched to
 * malformed/incomplete/error shapes without any paid provider.
 */
public final class FakeResponsesProvider implements AutoCloseable {
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<String> keys = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile boolean malformed;
    public volatile boolean incomplete;
    public volatile boolean refuse;
    public volatile boolean duplicateCallId;
    public volatile boolean wrongToolName;
    public volatile boolean badArguments;
    public volatile boolean omitStore;
    public volatile String toolName = "probe_echo";
    public volatile String arguments = "{\"text\":\"probe ok\"}";
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public FakeResponsesProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body;
            if (malformed) {
                body = "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                body = mapper.writeValueAsBytes(response(request));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ObjectNode response(JsonNode request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("status", incomplete ? "incomplete" : refuse ? "refusal" : "completed");
        if (status != 200) {
            return root;
        }
        ArrayNode output = root.putArray("output");
        boolean wantsTools = !request.path("tool_choice").asText("none").equals("none")
                && request.has("tools") && !refuse;
        if (wantsTools) {
            ObjectNode call = output.addObject();
            call.put("type", "function_call");
            call.put("id", "item_0");
            call.put("call_id", duplicateCallId ? "call_0" : "call_" + requests.size());
            call.put("name", wrongToolName ? "not_in_catalog" : toolName);
            call.put("arguments", badArguments ? "{\"text\":\"probe ok\",\"extra\":1}" : arguments);
            if (duplicateCallId) {
                ObjectNode twin = output.addObject();
                twin.put("type", "function_call");
                twin.put("id", "item_1");
                twin.put("call_id", "call_0");
                twin.put("name", "probe_echo");
                twin.put("arguments", "{\"text\":\"probe ok\"}");
            }
        } else if (refuse) {
            ObjectNode refusal = output.addObject();
            refusal.put("type", "refusal");
            refusal.put("id", "ref_0");
        } else {
            ObjectNode message = output.addObject();
            message.put("type", "message");
            message.put("id", "msg_0");
            message.put("role", "assistant");
            message.put("status", "completed");
            ArrayNode content = message.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "output_text");
            text.put("text", "responses final answer");
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 11);
        usage.put("output_tokens", 7);
        return root;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
