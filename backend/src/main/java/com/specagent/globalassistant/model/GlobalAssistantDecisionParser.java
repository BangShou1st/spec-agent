package com.specagent.globalassistant.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strict decision parse. Fail-closed on any schema violation.
 */
@Component
public class GlobalAssistantDecisionParser {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "assistantText", "statusText", "toolRequest", "uiAction", "requiresUserInput", "done");
    private static final Set<String> TOOL_FIELDS = Set.of("capabilityId", "arguments");
    private static final Set<String> UI_FIELDS = Set.of("destination", "resourceId");
    private final ObjectMapper mapper;
    public GlobalAssistantDecisionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public GlobalAssistantDecision parse(String content) {
        if (content == null || content.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model response");
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "Model response must be exactly one JSON object");
        }
        JsonNode root;
        try {
            root = mapper.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(trimmed);
        } catch (Exception ex) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Model response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Decision must be a JSON object");
        }
        for (java.util.Iterator<String> names = root.fieldNames(); names.hasNext();) {
            String field = names.next();
            if (!TOP_LEVEL_FIELDS.contains(field)) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "Unknown decision field: " + field);
            }
        }
        String assistantText = strictTextOrNull(root, "assistantText");
        String statusText = strictTextOrNull(root, "statusText");
        GlobalAssistantDecision.ToolRequest toolRequest = null;
        if (root.has("toolRequest") && !root.get("toolRequest").isNull()) {
            JsonNode tool = root.get("toolRequest");
            if (!tool.isObject()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest must be an object");
            }
            for (java.util.Iterator<String> names = tool.fieldNames(); names.hasNext();) {
                String field = names.next();
                if (!TOOL_FIELDS.contains(field)) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "Unknown toolRequest field: " + field);
                }
            }
            String capabilityId = strictTextOrNull(tool, "capabilityId");
            if (capabilityId == null || capabilityId.isBlank()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest.capabilityId is required");
            }
            Map<String, Object> arguments = Map.of();
            if (tool.has("arguments") && !tool.get("arguments").isNull()) {
                if (!tool.get("arguments").isObject()) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest.arguments must be an object");
                }
                arguments = new LinkedHashMap<>();
                var fields = tool.get("arguments").fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    arguments.put(entry.getKey(), jsonValue(entry.getValue()));
                }
            }
            toolRequest = new GlobalAssistantDecision.ToolRequest(capabilityId.trim(), arguments);
        }
        GlobalAssistantDecision.UiAction uiAction = null;
        if (root.has("uiAction") && !root.get("uiAction").isNull()) {
            JsonNode ui = root.get("uiAction");
            if (!ui.isObject()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "uiAction must be an object");
            }
            for (java.util.Iterator<String> names = ui.fieldNames(); names.hasNext();) {
                String field = names.next();
                if (!UI_FIELDS.contains(field)) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "Unknown uiAction field: " + field);
                }
            }
            String destination = strictTextOrNull(ui, "destination");
            if (destination == null || destination.isBlank()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "uiAction.destination is required");
            }
            GlobalAssistantDecision.UiDestination dest;
            try {
                dest = GlobalAssistantDecision.UiDestination.fromCode(destination);
            } catch (IllegalArgumentException ex) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Unknown UI destination: " + destination);
            }
            String resourceId = strictTextOrNull(ui, "resourceId");
            uiAction = new GlobalAssistantDecision.UiAction(dest, resourceId);
        }
        boolean requiresUserInput = false;
        if (root.has("requiresUserInput") && !root.get("requiresUserInput").isNull()) {
            JsonNode flag = root.get("requiresUserInput");
            if (!flag.isBoolean()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "requiresUserInput must be a boolean");
            }
            requiresUserInput = flag.booleanValue();
        }
        if (!root.has("done") || root.get("done").isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "done is required");
        }
        if (!root.get("done").isBoolean()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "done must be a boolean");
        }
        boolean done = root.get("done").booleanValue();
        return new GlobalAssistantDecision(assistantText, statusText, toolRequest, uiAction, requiresUserInput, done);
    }
    private String strictTextOrNull(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isTextual()) {
            return value.asText();
        }
        throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                field + " must be a string when present");
    }
    private Object jsonValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        try {
            return mapper.convertValue(node, Object.class);
        } catch (Exception ex) {
            return node.toString();
        }
    }
}
