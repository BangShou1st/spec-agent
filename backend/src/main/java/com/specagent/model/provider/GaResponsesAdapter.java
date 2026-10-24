package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GA Responses protocol conversion. Host-controlled stateless full-history
 * replay: every request rebuilds the complete {@code input} array, sets
 * {@code store:false}, and never uses {@code previous_response_id}.
 */
public final class GaResponsesAdapter {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Map<String, Object> requestBody(GaModelContract.Request request, String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("pinned model required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Object> input = new ArrayList<>();
        for (GaModelContract.Message message : request.messages()) {
            switch (message.role()) {
                case "system" -> input.add(instruction(message.content()));
                case "user", "assistant" -> input.add(roleMessage(message));
                case "tool" -> input.add(functionCallOutput(message));
                default -> throw new IllegalArgumentException("Unsupported GA message role");
            }
        }
        body.put("input", input);
        body.put("store", false);
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(tool -> {
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("type", "function");
                definition.put("name", tool.name());
                definition.put("description", tool.description());
                definition.put("parameters", tool.parameters());
                return definition;
            }).toList());
            body.put("tool_choice", toolChoice(request.toolChoice()));
            body.put("parallel_tool_calls", false);
        }
        body.put("max_output_tokens", request.maxOutputTokens());
        return body;
    }

    public GaModelContract.Response validateResponse(GaModelContract.Request request,
            GaModelContract.Response response) {
        if (("none".equals(request.toolChoice()) && !response.toolCalls().isEmpty())
                || ("required".equals(request.toolChoice()) && response.toolCalls().isEmpty())) {
            throw new IllegalArgumentException("GA native response violates tool choice");
        }
        Set<String> names = new HashSet<>();
        request.tools().forEach(tool -> names.add(tool.name()));
        Set<String> priorIds = new HashSet<>();
        request.messages().stream()
                .flatMap(message -> message.toolCalls().stream())
                .forEach(call -> priorIds.add(call.id()));
        for (GaModelContract.ToolCall call : response.toolCalls()) {
            if (!names.contains(call.name()) || priorIds.contains(call.id())) {
                throw new IllegalArgumentException("GA native tool identity not authorized");
            }
        }
        return response;
    }

    /** Parses one non-stream Responses object into the provider-neutral GA contract. */
    public GaModelContract.Response parse(JsonNode root) {
        if (root == null) throw invalid();
        StringBuilder content = new StringBuilder();
        List<GaModelContract.ToolCall> calls = new ArrayList<>();
        try {
            require(root.isObject(), "object required");
            JsonNode error = root.get("error");
            require(error == null || error.isNull(), "provider error");
            require("completed".equals(text(root.get("status"), 32)), "response not completed");
            require(!isPresent(root.get("incomplete_details")), "response incomplete");
            JsonNode output = root.get("output");
            require(output != null && output.isArray() && !output.isEmpty(), "output required");
            Set<String> callIds = new HashSet<>();
            for (JsonNode item : output) {
                parseOutputItem(item, content, calls, callIds);
            }
            JsonNode usage = root.get("usage");
            require(usage != null && usage.isObject(), "usage required");
            boolean hasCalls = !calls.isEmpty();
            require(hasCalls || !content.isEmpty(), "empty response");
            return new GaModelContract.Response(GaModelContract.VERSION,
                    content.toString(), calls, hasCalls ? "tool_calls" : "stop",
                    new GaModelContract.Usage(count(usage, "input_tokens"), count(usage, "output_tokens")));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private void parseOutputItem(JsonNode item, StringBuilder content,
            List<GaModelContract.ToolCall> calls, Set<String> callIds) throws Exception {
        require(item != null && item.isObject(), "output item required");
        String type = text(item.get("type"), 64);
        switch (type) {
            case "message" -> {
                require(!item.has("status") || "completed".equals(text(item.get("status"), 32)),
                        "incomplete message");
                JsonNode parts = item.get("content");
                require(parts != null && parts.isArray() && !parts.isEmpty(), "message content required");
                for (JsonNode part : parts) {
                    require(part.isObject() && "output_text".equals(text(part.get("type"), 64)),
                            "unsupported message part");
                    content.append(text(part.get("text"), 131072));
                }
            }
            case "function_call" -> {
                String callId = requiredText(item, "call_id", 128);
                require(callIds.add(callId), "duplicate call id");
                String name = requiredText(item, "name", 64);
                require(name.matches("[A-Za-z0-9_-]{1,64}"), "invalid tool name");
                String rawArguments = requiredText(item, "arguments", 131072);
                JsonNode arguments = mapper.readTree(rawArguments);
                require(arguments != null && arguments.isObject(), "tool arguments object required");
                Map<String, Object> converted = mapper.convertValue(arguments, new TypeReference<>() {});
                calls.add(new GaModelContract.ToolCall(callId, name, converted));
            }
            default -> throw invalid();
        }
    }

    private static Map<String, Object> instruction(String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "system");
        item.put("content", content);
        return item;
    }

    private static Map<String, Object> roleMessage(GaModelContract.Message message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", message.role());
        item.put("content", message.content() == null ? "" : message.content());
        if (!message.toolCalls().isEmpty()) {
            item.put("tool_calls", message.toolCalls().stream().map(call -> {
                Map<String, Object> nativeCall = new LinkedHashMap<>();
                nativeCall.put("call_id", call.id());
                nativeCall.put("name", call.name());
                nativeCall.put("arguments", argumentsJson(call.arguments()));
                return nativeCall;
            }).toList());
        }
        return item;
    }

    private static Map<String, Object> functionCallOutput(GaModelContract.Message message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", message.toolCallId());
        item.put("output", message.content());
        return item;
    }

    private static String toolChoice(String choice) {
        return switch (choice) {
            case "auto", "none", "required" -> choice;
            default -> throw new IllegalArgumentException("invalid tool choice");
        };
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        try {
            return new ObjectMapper().writeValueAsString(arguments);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool argument object");
        }
    }

    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static String requiredText(JsonNode node, String field, int limit) {
        return text(node.get(field), limit);
    }

    private static String text(JsonNode node, int limit) {
        require(node != null && node.isTextual() && !node.textValue().isEmpty()
                && node.textValue().length() <= limit, "invalid text");
        return node.textValue();
    }

    private static int count(JsonNode usage, String field) {
        JsonNode value = usage.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
                "invalid usage");
        return value.intValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid GA Responses provider response");
    }
}
