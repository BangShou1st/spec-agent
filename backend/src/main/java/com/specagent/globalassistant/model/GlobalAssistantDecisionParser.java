package com.specagent.globalassistant.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strict discriminated decision parse V2. Fail-closed on any schema violation.
 * Parses {@code kind} first, then only the corresponding branch. No legacy
 * compatibility path, no leniency.
 */
@Component
public class GlobalAssistantDecisionParser {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "kind", "assistantText", "toolRequest", "uiAction");
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "statusText", "requiresUserInput", "done");
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
            if (LEGACY_FIELDS.contains(field)) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "Legacy decision field is not accepted in V2: " + field);
            }
            if (!TOP_LEVEL_FIELDS.contains(field)) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "Unknown decision field: " + field);
            }
        }
        if (!root.has("kind") || root.get("kind").isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "kind is required");
        }
        if (!root.get("kind").isTextual()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "kind must be a string");
        }
        GlobalAssistantDecision.DecisionKind kind;
        try {
            kind = GlobalAssistantDecision.DecisionKind.fromCode(root.get("kind").asText());
        } catch (IllegalArgumentException ex) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "Unknown decision kind: " + root.get("kind").asText());
        }
        return switch (kind) {
            case TOOL -> parseTool(root);
            case CLARIFY -> parseClarify(root);
            case NAVIGATE -> parseNavigate(root);
            case FINAL -> parseFinal(root);
        };
    }
    private GlobalAssistantDecision parseTool(JsonNode root) {
        if (root.has("assistantText")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "TOOL decisions must not carry assistantText");
        }
        if (root.has("uiAction")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "TOOL decisions must not carry uiAction");
        }
        if (!root.has("toolRequest") || root.get("toolRequest").isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "TOOL decisions require toolRequest");
        }
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
        if (!tool.has("arguments") || tool.get("arguments").isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest.arguments is required");
        }
        if (!tool.get("arguments").isObject()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "toolRequest.arguments must be an object");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        var fields = tool.get("arguments").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            arguments.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        GlobalAssistantDecision.ToolRequest toolRequest =
                new GlobalAssistantDecision.ToolRequest(capabilityId.trim(), arguments);
        return new GlobalAssistantDecision(GlobalAssistantDecision.DecisionKind.TOOL, null, toolRequest, null);
    }
    private GlobalAssistantDecision parseClarify(JsonNode root) {
        if (root.has("toolRequest")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "CLARIFY decisions must not carry toolRequest");
        }
        if (root.has("uiAction")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "CLARIFY decisions must not carry uiAction");
        }
        String assistantText = requireText(root, "assistantText");
        return new GlobalAssistantDecision(GlobalAssistantDecision.DecisionKind.CLARIFY, assistantText, null, null);
    }
    private GlobalAssistantDecision parseNavigate(JsonNode root) {
        if (root.has("toolRequest")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "NAVIGATE decisions must not carry toolRequest");
        }
        if (!root.has("uiAction") || root.get("uiAction").isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "NAVIGATE decisions require uiAction");
        }
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
        GlobalAssistantDecision.UiAction uiAction = new GlobalAssistantDecision.UiAction(dest, resourceId);
        String assistantText = null;
        if (root.has("assistantText")) {
            JsonNode textNode = root.get("assistantText");
            if (textNode.isNull()) {
                assistantText = null;
            } else if (textNode.isTextual()) {
                assistantText = textNode.asText();
            } else {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "assistantText must be a string when present");
            }
        }
        return new GlobalAssistantDecision(
                GlobalAssistantDecision.DecisionKind.NAVIGATE, assistantText, null, uiAction);
    }
    private GlobalAssistantDecision parseFinal(JsonNode root) {
        if (root.has("toolRequest")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "FINAL decisions must not carry toolRequest");
        }
        if (root.has("uiAction")) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "FINAL decisions must not carry uiAction");
        }
        String assistantText = requireText(root, "assistantText");
        return new GlobalAssistantDecision(GlobalAssistantDecision.DecisionKind.FINAL, assistantText, null, null);
    }
    private String requireText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", field + " is required");
        }
        JsonNode value = node.get(field);
        if (value.isTextual()) {
            return value.asText();
        }
        throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                field + " must be a string when present");
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
