package com.specagent.globalassistant.model;

import java.util.Map;

/**
 * Discriminated decision contract V2. Exactly one decision kind per model
 * output. No combinatorial flags.
 */
public record GlobalAssistantDecision(
        DecisionKind kind,
        String assistantText,
        ToolRequest toolRequest,
        UiAction uiAction) {
    public enum DecisionKind {
        TOOL,
        CLARIFY,
        NAVIGATE,
        FINAL;
        public static DecisionKind fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("Decision kind must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
    public record ToolRequest(String capabilityId, Map<String, Object> arguments) {
        public ToolRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
    public record UiAction(UiDestination destination, String resourceId) {
    }
    public enum UiDestination {
        PROJECT,
        PROJECTS,
        SKILLS,
        CONNECTIONS,
        SETTINGS;
        public static UiDestination fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("UI destination must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
}
