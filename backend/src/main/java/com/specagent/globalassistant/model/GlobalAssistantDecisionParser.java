package com.specagent.globalassistant.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Strict decision parse. Fail-closed on any schema violation.
 */
@Component
public class GlobalAssistantDecisionParser {
    private final ObjectMapper mapper;
    public GlobalAssistantDecisionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public GlobalAssistantDecision parse(String content) {
        if (content == null || content.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model response");
        }
        String trimmed = content.trim();
        JsonNode root;
        try {
            root = mapper.readTree(extractJson(trimmed));
        } catch (Exception ex) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Model response is not valid JSON");
        }
        if (!root.isObject()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Decision must be a JSON object");
        }
        String assistantText = textOrNull(root, "assistantText");
        String statusText = textOrNull(root, "statusText");
        GlobalAssistantDecision.ToolRequest toolRequest = null;
        if (root.has("toolRequest") && !root.get("toolRequest").isNull()) {
            JsonNode tool = root.get("toolRequest");
            if (!tool.isObject()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest must be an object");
            }
            String capabilityId = textOrNull(tool, "capabilityId");
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
            String destination = textOrNull(ui, "destination");
            if (destination == null || destination.isBlank()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "uiAction.destination is required");
            }
            GlobalAssistantDecision.UiDestination dest;
            try {
                dest = GlobalAssistantDecision.UiDestination.fromCode(destination);
            } catch (IllegalArgumentException ex) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Unknown UI destination: " + destination);
            }
            String resourceId = textOrNull(ui, "resourceId");
            uiAction = new GlobalAssistantDecision.UiAction(dest, resourceId);
        }
        boolean requiresUserInput = root.has("requiresUserInput") && root.get("requiresUserInput").asBoolean(false);
        boolean done = root.has("done") && root.get("done").asBoolean(false);
        return new GlobalAssistantDecision(assistantText, statusText, toolRequest, uiAction, requiresUserInput, done);
    }
    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("{")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
    private String textOrNull(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isTextual()) {
            return value.asText();
        }
        return null;
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
