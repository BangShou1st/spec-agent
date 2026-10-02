package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.GaModelContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native GA conversion only. No settings, credentials or retry. */
public final class GaChatCompletionsAdapter {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Map<String, Object> requestBody(GaModelContract.Request request, String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("pinned model required");
        List<Map<String, Object>> messages = new ArrayList<>();
        for (GaModelContract.Message message : request.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                item.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                        "id", call.id(), "type", "function", "function", Map.of(
                                "name", call.name(), "arguments", argumentsJson(call.arguments())))).toList());
            }
            messages.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", request.stream());
        body.put("max_tokens", request.maxOutputTokens());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(tool -> Map.of(
                    "type", "function", "function", Map.of("name", tool.name(),
                            "description", tool.description(), "parameters", tool.parameters()))).toList());
            body.put("tool_choice", request.toolChoice());
            body.put("parallel_tool_calls", false);
        }
        return body;
    }

    /** Provider SSE stays internal; the broker may release separate candidate text frames. */
    public Map<String, Object> streamingRequestBody(GaModelContract.Request request, String model) {
        Map<String, Object> body = requestBody(request, model);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    public GaModelContract.Response validateResponse(GaModelContract.Request request, GaModelContract.Response response) {
        if (("none".equals(request.toolChoice()) && !response.toolCalls().isEmpty())
                || ("required".equals(request.toolChoice()) && response.toolCalls().isEmpty()))
            throw new IllegalArgumentException("GA native response violates tool choice");
        var names = request.tools().stream().map(GaModelContract.Tool::name).collect(java.util.stream.Collectors.toSet());
        var priorIds = request.messages().stream().flatMap(message -> message.toolCalls().stream())
                .map(GaModelContract.ToolCall::id).collect(java.util.stream.Collectors.toSet());
        for (var call : response.toolCalls()) {
            if (!names.contains(call.name()) || priorIds.contains(call.id()))
                throw new IllegalArgumentException("GA native tool identity not authorized");
        }
        return response;
    }

    public GaModelContract.Response parse(String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("GA provider response exceeds limit");
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || root.has("error")) throw new IllegalArgumentException();
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() != 1) throw new IllegalArgumentException();
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject() || !"assistant".equals(message.path("role").asText()))
                throw new IllegalArgumentException();
            JsonNode content = message.get("content");
            if (content != null && !content.isNull() && !content.isTextual()) throw new IllegalArgumentException();
            List<GaModelContract.ToolCall> calls = new ArrayList<>();
            JsonNode nativeCalls = message.get("tool_calls");
            if (nativeCalls != null) {
                if (!nativeCalls.isArray() || nativeCalls.size() > 5) throw new IllegalArgumentException();
                for (JsonNode call : nativeCalls) {
                    if (!"function".equals(call.path("type").asText())) throw new IllegalArgumentException();
                    JsonNode function = call.get("function");
                    if (function == null || !function.path("arguments").isTextual()) throw new IllegalArgumentException();
                    JsonNode args = mapper.readTree(function.get("arguments").textValue());
                    if (args == null || !args.isObject()) throw new IllegalArgumentException();
                    calls.add(new GaModelContract.ToolCall(requiredText(call, "id"),
                            requiredText(function, "name"), mapper.convertValue(args, new TypeReference<>() {})));
                }
            }
            JsonNode usage = root.get("usage");
            if (usage == null || !usage.isObject()) throw new IllegalArgumentException();
            return new GaModelContract.Response(GaModelContract.VERSION,
                    content == null || content.isNull() ? "" : content.textValue(), calls,
                    requiredText(choice, "finish_reason"), new GaModelContract.Usage(
                            requiredCount(usage, "prompt_tokens"), requiredCount(usage, "completion_tokens")));
        } catch (Exception ex) {
            // Never echo provider body, argument contents or private reasoning.
            throw new IllegalArgumentException("Invalid GA native provider response");
        }
    }

    private String argumentsJson(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool argument object");
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static int requiredCount(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IllegalArgumentException();
        return value.intValue();
    }
}
