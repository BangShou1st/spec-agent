package com.specagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic OpenAI Responses fixture: emits function_call items with
 * call_id, accepts function_call_output replay, and can be switched to
 * malformed/incomplete/error shapes without any paid provider.
 */
public final class FakeResponsesProvider implements AutoCloseable {
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<String> keys = new CopyOnWriteArrayList<>();
    public final List<String> rejections = new CopyOnWriteArrayList<>();
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
    public volatile boolean multiTool;
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public FakeResponsesProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String rejection = validate(request);
            if (rejection != null) {
                rejections.add(rejection);
                byte[] error = mapper.writeValueAsBytes(Map.of(
                        "error", Map.of("message", "protocol violation: " + rejection)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
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
        if (wantsTools && !incomplete) {
            int count = multiTool && requests.size() == 1 ? 2 : 1;
            if (duplicateCallId) count = 2;
            for (int index = 0; index < count; index++) {
                ObjectNode call = output.addObject();
                call.put("type", "function_call");
                call.put("id", "item_" + index);
                if (duplicateCallId) {
                    call.put("call_id", "call_0");
                } else {
                    call.put("call_id", "call_" + requests.size() + "_" + index);
                }
                call.put("name", wrongToolName ? "not_in_catalog" : toolName);
                call.put("arguments", badArguments ? "{\"text\":\"probe ok\",\"extra\":1}" : arguments);
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

    /**
     * Strict inbound protocol gate: this fake exists to catch protocol errors,
     * not to accept whatever the client sends. Returns the rejection reason, or
     * null when the request is a legal Responses function-calling history.
     */
    private String validate(JsonNode request) {
        if (!request.isObject()) return "request must be an object";
        if (request.has("messages")) return "chat completions messages field is forbidden";
        if (request.has("previous_response_id")) return "previous_response_id is forbidden";
        if (!request.path("store").isBoolean() || request.path("store").asBoolean()) {
            return "store:false is required";
        }
        JsonNode input = request.get("input");
        if (input == null || !input.isArray() || input.isEmpty()) return "input array required";
        Set<String> calledIds = new LinkedHashSet<>();
        Set<String> answeredIds = new LinkedHashSet<>();
        boolean sawFinalText = false;
        for (JsonNode item : input) {
            if (!item.isObject()) return "input item must be an object";
            String type = item.path("type").asText(null);
            if (type == null) {
                String role = item.path("role").asText("");
                if (!Set.of("system", "user", "assistant").contains(role)) {
                    return "input message requires a known role";
                }
                if (item.has("tool_calls")) {
                    return "assistant tool_calls is chat-completions shape; use function_call items";
                }
                if (item.has("tool_call_id")) {
                    return "tool_call_id is chat-completions shape; use function_call_output items";
                }
                if (!item.path("content").isTextual()) return "message content must be text";
                if ("assistant".equals(role) && !item.path("content").asText().isEmpty()) {
                    sawFinalText = true;
                }
                continue;
            }
            switch (type) {
                case "function_call" -> {
                    String callId = item.path("call_id").asText(null);
                    String name = item.path("name").asText(null);
                    String arguments = item.path("arguments").asText(null);
                    if (callId == null || callId.isBlank()) return "function_call.call_id required";
                    if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                        return "function_call.name invalid";
                    }
                    if (arguments == null) return "function_call.arguments required";
                    try {
                        JsonNode parsed = new ObjectMapper().readTree(arguments);
                        if (parsed == null || !parsed.isObject()) {
                            return "function_call.arguments must be a JSON object";
                        }
                    } catch (Exception ex) {
                        return "function_call.arguments is not valid JSON";
                    }
                    if (!calledIds.add(callId)) return "duplicate function_call.call_id: " + callId;
                    if (answeredIds.contains(callId)) return "function_call after its output: " + callId;
                    if (sawFinalText) return "final assistant text appeared before tool history ended";
                }
                case "function_call_output" -> {
                    String callId = item.path("call_id").asText(null);
                    if (callId == null || callId.isBlank()) return "function_call_output.call_id required";
                    if (!calledIds.contains(callId)) return "orphan function_call_output: " + callId;
                    if (!answeredIds.add(callId)) return "duplicate function_call_output.call_id: " + callId;
                    if (!item.path("output").isTextual()) return "function_call_output.output must be text";
                }
                default -> {
                    return "unsupported input item type: " + type;
                }
            }
        }
        for (String id : calledIds) {
            if (!answeredIds.contains(id)) return "function_call without output: " + id;
        }
        JsonNode tools = request.get("tools");
        if (tools != null && (!tools.isArray() || tools.size() > 12)) return "tools must be a bounded array";
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
