package com.specagent.model.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Separate native GA model wire contract; never widens the Project Agent broker. */
public final class GaModelContract {
    public static final String VERSION = "ga-model-inference.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    private GaModelContract() {}

    public static Request readRequest(String json) {
        return read(json, Request.class);
    }

    public static Response readResponse(String json) {
        return read(json, Response.class);
    }

    private static <T> T read(String json, Class<T> type) {
        require(json != null && json.getBytes(StandardCharsets.UTF_8).length <= 262144,
                "GA model wire limit exceeded");
        try {
            var tree = MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException();
            for (String field : List.of("messages", "tools", "toolCalls")) {
                if (tree.has(field) && !tree.get(field).isArray()) throw new IllegalArgumentException();
            }
            if (tree.has("messages")) {
                for (var message : tree.get("messages")) {
                    if (message.has("toolCalls") && !message.get("toolCalls").isArray())
                        throw new IllegalArgumentException();
                }
            }
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            // Never include raw JSON, prompts, arguments or Jackson value snippets.
            throw new IllegalArgumentException("Invalid GA model contract");
        }
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
        public ToolCall {
            text(id, 128);
            toolName(name);
            require(arguments != null, "tool arguments required");
            arguments = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
        }
    }

    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public Message {
            require(role != null && Set.of("system", "user", "assistant", "tool").contains(role),
                    "unsupported role");
            require(content != null && content.length() <= 131072, "invalid message content");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            require(toolCalls.size() <= 5, "too many tool calls");
            require(toolCalls.isEmpty() || "assistant".equals(role), "only assistant carries calls");
            require("tool".equals(role) == (toolCallId != null), "tool result identity required");
            if (toolCallId != null) text(toolCallId, 128);
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
            require(VERSION.equals(protocolVersion), "unknown protocol");
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
    }

    public record Usage(int promptTokens, int completionTokens) {
        public Usage {
            require(promptTokens >= 0 && completionTokens >= 0, "negative token usage");
        }
    }

    public record Response(String protocolVersion, String content, List<ToolCall> toolCalls,
                           String finishReason, Usage usage) {
        public Response {
            require(VERSION.equals(protocolVersion), "unknown protocol");
            require(content != null && content.length() <= 131072, "invalid response content");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            require(toolCalls.size() <= 5, "too many tool calls");
            require("stop".equals(finishReason) || "tool_calls".equals(finishReason), "unsuccessful model finish");
            require(!toolCalls.isEmpty() == "tool_calls".equals(finishReason), "finish reason/call mismatch");
            require(!content.isBlank() || !toolCalls.isEmpty(), "empty response");
            require(usage != null, "usage required");
            Set<String> ids = new HashSet<>();
            for (ToolCall call : toolCalls) require(ids.add(call.id()), "duplicate tool call identity");
        }
    }

    private static void toolName(String name) {
        require(name != null && name.matches("[A-Za-z0-9_-]{1,64}"), "invalid tool name");
    }

    private static void text(String text, int limit) {
        require(text != null && !text.isBlank() && text.length() <= limit, "invalid text field");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
