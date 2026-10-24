package com.specagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Strict Fake Anthropic Messages provider. This is an inbound protocol
 * assertion service, not a canned JSON emitter: it rejects (HTTP 400) any
 * request that is not a legal Anthropic Messages tool-calling or structured
 * output conversation, including Chat Completions / Responses shapes, forged
 * beta headers, missing max_tokens, illegal tool_choice, misplaced tool_result
 * blocks and split tool results. It can be switched to malformed, truncated,
 * refusal, rate-limited and unauthorized shapes without any paid provider.
 */
public final class FakeAnthropicMessagesProvider implements AutoCloseable {
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<String> responses = new CopyOnWriteArrayList<>();
    public final List<String> apiKeys = new CopyOnWriteArrayList<>();
    public final List<String> versions = new CopyOnWriteArrayList<>();
    public final List<String> rejections = new CopyOnWriteArrayList<>();

    public volatile int status = 200;
    public volatile boolean malformed;
    public volatile boolean badJsonBody;
    public volatile boolean refusalStop;
    public volatile boolean pauseTurnStop;
    public volatile boolean maxTokensStop;
    public volatile boolean nullStop;
    public volatile String finalText = "anthropic final answer";

    // Project JSON envelope controls (single place; variants are exclusive).
    public volatile String envelopeInnerJson = "{\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"}";
    public volatile EnvelopeFailure envelopeFailure = EnvelopeFailure.NONE;

    // Tool response controls.
    public volatile String toolName = "probe_echo";
    public volatile String secondToolName = "project_list";
    public volatile String toolArguments = "{\"text\":\"probe ok\"}";
    public volatile String secondToolArguments;
    public volatile ToolShape toolShape = ToolShape.NONE;
    public volatile boolean wrongToolName;
    public volatile boolean duplicateToolUseId;
    public volatile boolean toolInputNotObject;

    public enum EnvelopeFailure {
        NONE, MISSING_JSON_FIELD, EXTRA_FIELD, INNER_NOT_OBJECT, INNER_INVALID_JSON,
        DUPLICATE_KEY, INVALID_UNICODE, OVERSIZED, RAW_NO_ENVELOPE, EMPTY_TEXT
    }

    public enum ToolShape {
        NONE, SINGLE, MULTI, TEXT_THEN_TOOL, TOOL_THEN_TEXT, THINKING_THEN_TOOL,
        DUPLICATE_ID, MISSING_ID, STOP_REASON_MISMATCH
    }

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public FakeAnthropicMessagesProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            JsonNode request = null;
            if (!badJsonBody) {
                request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
            }
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            versions.add(exchange.getRequestHeaders().getFirst("anthropic-version"));
            String headerRejection = validateHeaders(exchange);
            String rejection = headerRejection != null ? headerRejection
                    : badJsonBody ? "unreadable body" : validate(request);
            if (rejection != null) {
                rejections.add(rejection);
                byte[] error = mapper.writeValueAsBytes(Map.of("type", "error",
                        "error", Map.of("type", "invalid_request_error",
                                "message", "protocol violation: " + rejection)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            byte[] body;
            if (badJsonBody) {
                body = "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (malformed) {
                body = "{\"content\":[{\"type\":\"text\",\"text\":\"unterminated".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
            } else {
                byte[] payload = mapper.writeValueAsBytes(response(request));
                responses.add(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                body = payload;
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

    private String validateHeaders(com.sun.net.httpserver.HttpExchange exchange) {
        if (exchange.getRequestHeaders().getFirst("anthropic-beta") != null) {
            return "forged or unnecessary anthropic-beta header";
        }
        if (!"2023-06-01".equals(exchange.getRequestHeaders().getFirst("anthropic-version"))) {
            return "anthropic-version header missing or wrong";
        }
        if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
            return "Authorization bearer header is not the Anthropic identity header";
        }
        return null;
    }

    private ObjectNode response(JsonNode request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "msg_" + requests.size());
        root.put("type", "message");
        root.put("role", "assistant");
        String stopReason = resolveStopReason();
        boolean stopOverride = nullStop || refusalStop || pauseTurnStop || maxTokensStop;
        boolean secondRound = request != null && request.path("messages").isArray()
                && request.path("messages").toString().contains("tool_result");
        boolean wantsTools = request != null && request.has("tools") && !request.path("tools").isEmpty()
                && !"none".equals(request.path("tool_choice").path("type").asText("none"));
        boolean structured = request != null && request.has("output_config");
        ArrayNode content = root.putArray("content");
        if (structured) {
            emitEnvelope(content);
        } else if (wantsTools && !secondRound && toolShape != ToolShape.NONE) {
            emitToolResponse(content);
            if (toolShape != ToolShape.STOP_REASON_MISMATCH && !stopOverride) {
                stopReason = "tool_use";
            }
        } else {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", finalText);
        }
        if (nullStop) {
            root.putNull("stop_reason");
        } else {
            root.put("stop_reason", stopReason);
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 13);
        usage.put("output_tokens", 5);
        return root;
    }

    private String resolveStopReason() {
        if (refusalStop) return "refusal";
        if (pauseTurnStop) return "pause_turn";
        if (maxTokensStop) return "max_tokens";
        return "end_turn";
    }

    private void emitEnvelope(ArrayNode content) {
        ObjectNode text = content.addObject();
        text.put("type", "text");
        String raw = switch (envelopeFailure) {
            case MISSING_JSON_FIELD -> "{\"other\":\"value\"}";
            case EXTRA_FIELD -> "{\"json\":\"" + escaped(envelopeInnerJson) + "\",\"extra\":1}";
            case INNER_NOT_OBJECT -> "{\"json\":\"[1,2,3]\"}";
            case INNER_INVALID_JSON -> "{\"json\":\"{not json\"}";
            case DUPLICATE_KEY -> "{\"json\":\"a\",\"json\":\"b\"}";
            case INVALID_UNICODE -> "{\"json\":\"\\ud800\"}";
            case OVERSIZED -> "{\"json\":\"" + "x".repeat(200000) + "\"}";
            case RAW_NO_ENVELOPE -> envelopeInnerJson;
            case EMPTY_TEXT -> "";
            case NONE -> "{\"json\":\"" + escaped(envelopeInnerJson) + "\"}";
        };
        text.put("text", raw);
    }

    private void emitToolResponse(ArrayNode content) {
        String name = wrongToolName ? "not_in_catalog" : toolName;
        String input = toolInputNotObject ? "[1,2]" : toolArguments;
        switch (toolShape) {
            case SINGLE -> addToolUse(content, "toolu_01", name, input);
            case MULTI -> {
                addToolUse(content, "toolu_01", name, input);
                addToolUse(content, "toolu_02", secondToolName,
                        secondToolArguments == null ? toolArguments : secondToolArguments);
            }
            case TEXT_THEN_TOOL -> {
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", "I will search first.");
                addToolUse(content, "toolu_01", name, input);
            }
            case TOOL_THEN_TEXT -> {
                addToolUse(content, "toolu_01", name, input);
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", "text after tool use");
            }
            case THINKING_THEN_TOOL -> {
                ObjectNode thinking = content.addObject();
                thinking.put("type", "thinking");
                thinking.put("thinking", "private reasoning");
                thinking.put("signature", "sig-private");
                addToolUse(content, "toolu_01", name, input);
            }
            case DUPLICATE_ID -> {
                addToolUse(content, "toolu_dup", name, input);
                addToolUse(content, "toolu_dup", secondToolName, toolArguments);
            }
            case MISSING_ID -> addToolUse(content, "", name, input);
            case STOP_REASON_MISMATCH -> addToolUse(content, "toolu_01", name, input);
            default -> {
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", finalText);
            }
        }
    }

    private void addToolUse(ArrayNode content, String id, String name, String input) {
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        if (!id.isEmpty()) block.put("id", id);
        block.put("name", name);
        try {
            block.set("input", mapper.readTree(input));
        } catch (Exception ex) {
            block.put("input", input);
        }
    }

    private static String escaped(String value) {
        // JSON 字符串值内不允许字面控制字符;换行/回行/制表必须转义,否则
        // 适配器的严格解析(正确地)拒绝整个 envelope。
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Strict inbound protocol gate. Returns the rejection reason, or null when
     * the request is a legal Anthropic Messages conversation.
     */
    private String validate(JsonNode request) {
        if (!request.isObject()) return "request must be an object";
        if (request.has("input") || request.has("instructions")) {
            return "responses API shape is forbidden";
        }
        if (request.has("previous_response_id")) return "previous_response_id is forbidden";
        if (request.has("response_format")) {
            return "chat completions response_format is forbidden; use output_config.format";
        }
        if (request.has("output_format")) return "deprecated output_format is forbidden";
        if (!request.path("model").isTextual() || request.path("model").asText().isBlank()) {
            return "model is required";
        }
        JsonNode maxTokens = request.get("max_tokens");
        if (maxTokens == null || !maxTokens.isIntegralNumber() || maxTokens.asInt() < 1) {
            return "max_tokens is required and must be a positive integer";
        }
        JsonNode outputConfig = request.get("output_config");
        if (outputConfig != null && !outputConfig.isNull()) {
            if (!outputConfig.isObject()) return "output_config must be an object";
            JsonNode format = outputConfig.get("format");
            if (format == null || !format.isObject()) return "output_config.format is required";
            if (!"json_schema".equals(format.path("type").asText())) {
                return "output_config.format.type must be json_schema";
            }
            if (format.path("schema").path("type").asText("").isEmpty()
                    || !"object".equals(format.path("schema").path("type").asText())) {
                return "output_config.format.schema must describe an object";
            }
        }
        JsonNode tools = request.get("tools");
        if (tools != null && !tools.isNull()) {
            if (!tools.isArray() || tools.size() > 12) return "tools must be a bounded array";
            for (JsonNode tool : tools) {
                if (!tool.isObject()) return "tool definition must be an object";
                if (tool.has("function") || tool.has("parameters")) {
                    return "chat completions tool shape is forbidden; use name/description/input_schema";
                }
                if (!tool.path("name").isTextual()
                        || !tool.path("name").asText().matches("[A-Za-z0-9_-]{1,64}")) {
                    return "tool name invalid";
                }
                JsonNode schema = tool.get("input_schema");
                if (schema == null || !schema.isObject()
                        || !"object".equals(schema.path("type").asText())) {
                    return "tools.input_schema must be an object schema";
                }
            }
        }
        JsonNode toolChoice = request.get("tool_choice");
        if (toolChoice != null && !toolChoice.isNull()) {
            if (!toolChoice.isObject()) return "tool_choice must be an object with a type";
            String type = toolChoice.path("type").asText("");
            if (!Set.of("auto", "any", "none", "tool").contains(type)) {
                return "illegal tool_choice structure";
            }
        }
        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return "messages array required";
        }
        Set<String> calledIds = new LinkedHashSet<>();
        Set<String> answeredIds = new LinkedHashSet<>();
        List<String> pendingIds = new ArrayList<>();
        boolean lastAssistantHadToolUse = false;
        boolean sawFinalAssistantText = false;
        for (JsonNode message : messages) {
            if (!message.isObject()) return "message must be an object";
            if (message.has("tool_calls")) {
                return "messages[].tool_calls is chat-completions shape; use tool_use blocks";
            }
            if (message.has("tool_call_id")) {
                return "messages[].tool_call_id is chat-completions shape; use tool_result blocks";
            }
            if (message.has("function_call") || message.has("function_call_output")) {
                return "responses function_call shape is forbidden";
            }
            String role = message.path("role").asText("");
            JsonNode contentNode = message.get("content");
            if (contentNode == null) return "message content required";
            if (contentNode.isTextual()) {
                if (!Set.of("user", "assistant").contains(role)) {
                    return "message role must be user or assistant";
                }
                if ("assistant".equals(role) && !contentNode.asText().isEmpty()) {
                    sawFinalAssistantText = true;
                }
                if ("assistant".equals(role)) lastAssistantHadToolUse = false;
                continue;
            }
            if (!contentNode.isArray() || contentNode.isEmpty()) {
                return "message content must be a string or a non-empty block array";
            }
            boolean sawToolResult = false;
            boolean sawTextBlock = false;
            switch (role) {
                case "user" -> {
                    for (JsonNode block : contentNode) {
                        if (!block.isObject()) return "content block must be an object";
                        String type = block.path("type").asText("");
                        if ("tool_result".equals(type)) {
                            if (sawTextBlock) {
                                return "tool_result blocks must precede any text block";
                            }
                            sawToolResult = true;
                            String id = block.path("tool_use_id").asText(null);
                            if (id == null || id.isBlank()) return "tool_result.tool_use_id required";
                            if (!calledIds.contains(id)) return "orphan tool_result: " + id;
                            if (!answeredIds.add(id)) return "duplicate tool_result: " + id;
                            if (!pendingIds.remove(id)) {
                                return "tool_result does not answer the immediately previous tool_use: " + id;
                            }
                            JsonNode resultContent = block.get("content");
                            if (resultContent == null || !resultContent.isTextual()) {
                                return "tool_result.content must be a string";
                            }
                        } else if ("text".equals(type)) {
                            sawTextBlock = true;
                        } else {
                            return "unsupported user content block: " + type;
                        }
                    }
                    if (sawToolResult && lastAssistantHadToolUse && !pendingIds.isEmpty()) {
                        return "tool results split across messages; all tool_use ids must be answered in one user message";
                    }
                    if (sawToolResult && !lastAssistantHadToolUse) {
                        return "tool_result message must immediately follow the assistant tool_use message";
                    }
                    lastAssistantHadToolUse = false;
                }
                case "assistant" -> {
                    boolean sawToolUse = false;
                    for (JsonNode block : contentNode) {
                        if (!block.isObject()) return "content block must be an object";
                        String type = block.path("type").asText("");
                        switch (type) {
                            case "text" -> {
                                if (sawToolUse) return "text block after tool_use is forbidden in history";
                                sawFinalAssistantText = true;
                            }
                            case "tool_use" -> {
                                sawToolUse = true;
                                String id = block.path("id").asText(null);
                                if (id == null || id.isBlank()) return "tool_use.id required";
                                if (!calledIds.add(id)) return "duplicate tool_use id: " + id;
                                if (answeredIds.contains(id)) return "tool_use after its result: " + id;
                                if (sawFinalAssistantText && pendingIds.isEmpty() && answeredIds.isEmpty()) {
                                    // final text before tool history is legal only once; keep permissive
                                }
                                String name = block.path("name").asText(null);
                                if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                                    return "tool_use.name invalid";
                                }
                                JsonNode input = block.get("input");
                                if (input == null || !input.isObject()) {
                                    return "tool_use.input must be an object";
                                }
                                pendingIds.add(id);
                            }
                            case "thinking", "redacted_thinking" -> {
                                return "thinking history blocks are forbidden";
                            }
                            default -> {
                                return "unsupported assistant content block: " + type;
                            }
                        }
                    }
                    lastAssistantHadToolUse = sawToolUse;
                }
                default -> {
                    return "unsupported message role: " + role;
                }
            }
        }
        if (!pendingIds.isEmpty()) return "tool_use without result: " + pendingIds.getFirst();
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
