package com.specagent.model.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Separate native GA model wire contract; never widens the Project Agent broker.
 *
 * <p>v2 is the Python-facing wire protocol: it carries only an opaque
 * {@code continuationRef} on the assistant message/response. v1 remains the
 * Java-internal representation used by protocol adapters; it may carry the
 * provider-specific material only after the host resolved and authorized a ref.
 */
public final class GaModelContract {
    public static final String VERSION = "ga-model-inference.v1";
    public static final String VERSION_2 = "ga-model-inference.v2";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    private GaModelContract() {}

    /** Reads either v1 or v2 into the strict internal representation. */
    public static Request readRequest(String json) {
        var envelope = readEnvelope(json);
        return switch (envelope.version()) {
            case VERSION -> parseRequest(envelope.tree(), VERSION, false);
            case VERSION_2 -> parseRequest(envelope.tree(), VERSION_2, true);
            default -> throw new IllegalArgumentException("Invalid GA model contract");
        };
    }

    /** Reads either v1 or v2; v1 may include already-resolved internal extra content. */
    public static Response readResponse(String json) {
        var envelope = readEnvelope(json);
        return switch (envelope.version()) {
            case VERSION -> parseResponse(envelope.tree(), VERSION, false);
            case VERSION_2 -> parseResponse(envelope.tree(), VERSION_2, true);
            default -> throw new IllegalArgumentException("Invalid GA model contract");
        };
    }

    private record Envelope(String version, JsonNode tree) {}

    private static final Set<String> REQUEST_FIELDS = Set.of("protocolVersion", "runId", "executionEpoch",
            "leaseId", "callId", "callType", "modelBindingId", "messages", "tools", "toolChoice",
            "maxOutputTokens", "stream");
    private static final Set<String> RESPONSE_FIELDS = Set.of("protocolVersion", "content", "toolCalls",
            "finishReason", "continuationRef", "usage");
    private static final Set<String> MESSAGE_FIELDS = Set.of("role", "content", "toolCalls", "toolCallId",
            "continuationRef", "extraContent");
    private static final Set<String> TOOL_CALL_FIELDS = Set.of("id", "name", "arguments", "extraContent");
    private static final Set<String> TOOL_FIELDS = Set.of("name", "description", "parameters");

    private static Envelope readEnvelope(String json) {
        require(json != null && json.getBytes(StandardCharsets.UTF_8).length <= 262144,
                "GA model wire limit exceeded");
        try {
            JsonNode tree = MAPPER.readTree(json);
            require(tree != null && tree.isObject(), "object required");
            JsonNode version = tree.get("protocolVersion");
            require(version != null && version.isTextual(), "protocol version required");
            String value = version.textValue();
            require(VERSION.equals(value) || VERSION_2.equals(value), "unknown protocol");
            return new Envelope(value, tree);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid GA model contract");
        }
    }

    private static Request parseRequest(JsonNode tree, String version, boolean wireV2) {
        try {
            fields(tree, REQUEST_FIELDS);
            List<Message> messages = new ArrayList<>();
            for (JsonNode node : requiredArray(tree, "messages", 128)) {
                fields(node, MESSAGE_FIELDS);
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode call : nullableArray(node, "toolCalls", 5)) {
                    fields(call, TOOL_CALL_FIELDS);
                    Map<String, Object> extra = null;
                    JsonNode extraNode = call.get("extraContent");
                    if (extraNode != null && !extraNode.isNull()) {
                        require(extraNode.isObject(), "invalid extra content");
                        require(!wireV2, "v2 forbids provider extra content");
                        extra = MAPPER.convertValue(extraNode, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    } else if (extraNode != null) {
                        throw new IllegalArgumentException("extra content must be object");
                    }
                    calls.add(new ToolCall(requiredText(call, "id", 128),
                            requiredText(call, "name", 64), object(call.get("arguments")), extra));
                }
                String ref = null;
                JsonNode refNode = node.get("continuationRef");
                if (refNode != null && !refNode.isNull()) {
                    require(Boolean.TRUE.equals(wireV2), "v1 forbids continuationRef");
                    validateContinuationRef(requiredText(node, "continuationRef", 80));
                }
                messages.add(new Message(requiredText(node, "role", 10),
                        text(node.get("content"), 131072), calls,
                        node.has("toolCallId") && !node.get("toolCallId").isNull()
                                ? requiredText(node, "toolCallId", 128) : null, ref, null));
            }
            List<Tool> tools = new ArrayList<>();
            for (JsonNode tool : nullableArray(tree, "tools", 12)) {
                fields(tool, TOOL_FIELDS);
                tools.add(new Tool(requiredText(tool, "name", 64),
                        requiredText(tool, "description", 8192), object(tool.get("parameters"))));
            }
            return new Request(version, uuid(tree.get("runId")), positiveLong(tree.get("executionEpoch")),
                    uuid(tree.get("leaseId")), uuid(tree.get("callId")),
                    requiredText(tree, "callType", 10), uuid(tree.get("modelBindingId")),
                    messages, tools, requiredText(tree, "toolChoice", 10),
                    boundedInt(tree.get("maxOutputTokens"), 1, 8192),
                    tree.path("stream").asBoolean(false));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid GA model contract");
        }
    }

    private static Response parseResponse(JsonNode tree, String version, boolean wireV2) {
        try {
            fields(tree, RESPONSE_FIELDS);
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : nullableArray(tree, "toolCalls", 5)) {
                fields(call, TOOL_CALL_FIELDS);
                Map<String, Object> extra = null;
                JsonNode extraNode = call.get("extraContent");
                if (extraNode != null && !extraNode.isNull()) {
                    require(extraNode.isObject(), "invalid extra content");
                    require(!wireV2, "v2 forbids provider extra content");
                    extra = MAPPER.convertValue(extraNode, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                }
                calls.add(new ToolCall(requiredText(call, "id", 128),
                        requiredText(call, "name", 64), object(call.get("arguments")), extra));
            }
            String ref = null;
            JsonNode refNode = tree.get("continuationRef");
            if (refNode != null && !refNode.isNull()) {
                require(wireV2, "v1 forbids continuationRef");
                validateContinuationRef(requiredText(tree, "continuationRef", 80));
                ref = refNode.textValue();
            }
            JsonNode usageNode = tree.get("usage");
            require(usageNode != null && usageNode.isObject(), "usage required");
            return new Response(version, text(tree.get("content"), 131072), calls,
                    requiredText(tree, "finishReason", 20), ref,
                    new Usage(boundedInt(usageNode.get("promptTokens"), 0, Integer.MAX_VALUE),
                            boundedInt(usageNode.get("completionTokens"), 0, Integer.MAX_VALUE)));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid GA model contract");
        }
    }

    /**
     * Internal ToolCall. {@code extraContent} is provider material resolved by
     * the Java host; it never appears on the v2 Python wire.
     */
    public record ToolCall(String id, String name, Map<String, Object> arguments,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> extraContent) {
        public ToolCall(String id, String name, Map<String, Object> arguments) {
            this(id, name, arguments, null);
        }

        public ToolCall {
            text(id, 128);
            toolName(name);
            require(arguments != null, "tool arguments required");
            arguments = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            if (extraContent != null) {
                require(extraContent.size() <= 16, "too many extra content entries");
                extraContent = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extraContent));
            }
        }
    }

    /** Internal message; ref and extraContent are host-resolved values, not wire fields. */
    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String continuationRef,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> extraContent) {
        public Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, toolCalls, toolCallId, null, null);
        }

        public Message {
            require(role != null && Set.of("system", "user", "assistant", "tool").contains(role),
                    "unsupported role");
            require(content != null && content.length() <= 131072, "invalid message content");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            require(toolCalls.size() <= 5, "too many tool calls");
            require(toolCalls.isEmpty() || "assistant".equals(role), "only assistant carries calls");
            require("tool".equals(role) == (toolCallId != null), "tool result identity required");
            if (toolCallId != null) text(toolCallId, 128);
            if (continuationRef != null) validateContinuationRef(continuationRef);
            require(continuationRef == null || "assistant".equals(role), "continuation requires assistant");
            require(continuationRef == null || !toolCalls.isEmpty(), "continuation requires tool calls");
            if (extraContent != null) require("assistant".equals(role) && !toolCalls.isEmpty(),
                    "continuation payload requires tool calls");
            require(!content.isBlank() || ("assistant".equals(role) && !toolCalls.isEmpty()), "empty message");
        }
    }

    public record Tool(String name, String description, Map<String, Object> parameters) {
        public Tool {
            toolName(name);
            text(description, 8192);
            require(parameters != null && "object".equals(parameters.get("type")), "object tool schema required");
            parameters = Map.copyOf(parameters);
        }
    }

    public record Request(String protocolVersion, UUID runId, long executionEpoch, UUID leaseId,
                          UUID callId, String callType, UUID modelBindingId, List<Message> messages,
                          List<Tool> tools, String toolChoice, int maxOutputTokens, boolean stream) {
        public Request {
            require(VERSION.equals(protocolVersion) || VERSION_2.equals(protocolVersion), "unknown protocol");
            require(runId != null && executionEpoch > 0 && leaseId != null && callId != null
                    && modelBindingId != null, "execution binding required");
            require(callType != null && Set.of("AGENT", "SUMMARY").contains(callType), "unknown call type");
            require(messages != null && !messages.isEmpty() && messages.size() <= 128, "invalid message count");
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            require(tools.size() <= 12, "too many tools");
            require(toolChoice != null && Set.of("auto", "none", "required").contains(toolChoice), "invalid tool choice");
            require(!"required".equals(toolChoice) || !tools.isEmpty(), "required choice needs tools");
            require(maxOutputTokens >= 1 && maxOutputTokens <= 8192, "invalid output budget");
            require(!"SUMMARY".equals(callType) || (tools.isEmpty() && "none".equals(toolChoice) && !stream),
                    "summary cannot use tools or stream");
            Set<String> names = new HashSet<>();
            for (Tool tool : tools) require(names.add(tool.name()), "duplicate tool name");
            Set<String> seen = new HashSet<>();
            Set<String> pending = new HashSet<>();
            for (Message message : messages) {
                if ("tool".equals(message.role())) {
                    require(pending.remove(message.toolCallId()), "orphan or duplicate tool result");
                } else {
                    require(pending.isEmpty(), "unanswered tool calls");
                    for (ToolCall call : message.toolCalls()) {
                        require(seen.add(call.id()), "duplicate tool call identity");
                        pending.add(call.id());
                    }
                }
            }
            require(pending.isEmpty(), "unfinished tool calls");
        }

        @JsonIgnore
        public boolean wireV2() { return VERSION_2.equals(protocolVersion); }
    }

    public record Usage(int promptTokens, int completionTokens) {
        public Usage {
            require(promptTokens >= 0 && completionTokens >= 0, "negative token usage");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String protocolVersion, String content, List<ToolCall> toolCalls,
                           String finishReason, String continuationRef, Usage usage) {
        public Response(String protocolVersion, String content, List<ToolCall> toolCalls,
                        String finishReason, Usage usage) {
            this(protocolVersion, content, toolCalls, finishReason, null, usage);
        }

        public Response {
            require(VERSION.equals(protocolVersion) || VERSION_2.equals(protocolVersion), "unknown protocol");
            require(content != null && content.length() <= 131072, "invalid response content");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            require(toolCalls.size() <= 5, "too many tool calls");
            require("stop".equals(finishReason) || "tool_calls".equals(finishReason), "unsuccessful model finish");
            require(!toolCalls.isEmpty() == "tool_calls".equals(finishReason), "finish reason/call mismatch");
            if (continuationRef != null) validateContinuationRef(continuationRef);
            require(continuationRef == null || !toolCalls.isEmpty(), "continuation requires tool calls");
            require(!content.isBlank() || !toolCalls.isEmpty(), "empty response");
            require(usage != null, "usage required");
            Set<String> ids = new HashSet<>();
            for (ToolCall call : toolCalls) require(ids.add(call.id()), "duplicate tool call identity");
        }

        /** Strips host-resolved provider material; used before ledger persistence and wire emission. */
        public Response sanitizeForWire(String targetVersion, String ref) {
            return new Response(targetVersion, content, toolCalls.stream()
                    .map(call -> new ToolCall(call.id(), call.name(), call.arguments(), null)).toList(),
                    finishReason, ref, usage);
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        require(value != null && value.isArray() && !value.isEmpty() && value.size() <= max, "invalid array");
        return value;
    }

    private static JsonNode nullableArray(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        require(value == null || (value.isArray() && value.size() <= max), "invalid array");
        return value == null ? MAPPER.createArrayNode() : value;
    }

    private static Map<String, Object> object(JsonNode value) {
        require(value != null && value.isObject(), "object required");
        return MAPPER.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject(), "object required");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(expected.containsAll(actual), "unknown fields");
    }

    private static String text(JsonNode value, int limit) {
        require(value != null && value.isTextual() && value.textValue().length() <= limit, "invalid text");
        return value.textValue();
    }

    private static String requiredText(JsonNode node, String field, int limit) {
        return text(node.get(field), limit);
    }

    private static UUID uuid(JsonNode value) {
        String raw = text(value, 36);
        try { return UUID.fromString(raw); }
        catch (Exception ex) { throw new IllegalArgumentException("invalid uuid"); }
    }

    private static long positiveLong(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong(), "invalid long");
        long parsed = value.longValue();
        require(parsed > 0, "invalid long");
        return parsed;
    }

    private static int boundedInt(JsonNode value, int min, int max) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "invalid int");
        int parsed = value.intValue();
        require(parsed >= min && parsed <= max, "invalid int");
        return parsed;
    }

    private static void validateContinuationRef(String value) {
        require(value != null && value.matches("gacr-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "invalid continuation reference");
    }

    private static void toolName(String name) {
        require(name != null && name.matches("[A-Za-z0-9_-]{1,64}"), "invalid tool name");
    }

    private static void text(String textValue, int limit) {
        require(textValue != null && !textValue.isBlank() && textValue.length() <= limit, "invalid text field");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
