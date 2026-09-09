package com.specagent.globalassistant.model;

import java.util.Map;

/**
 * Strict JSON logical contract. At most one primary tool per decision.
 */
public record GlobalAssistantDecision(
        String assistantText,
        String statusText,
        ToolRequest toolRequest,
        UiAction uiAction,
        boolean requiresUserInput,
        boolean done) {
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
