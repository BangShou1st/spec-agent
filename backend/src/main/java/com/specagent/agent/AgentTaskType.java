package com.specagent.agent;

/**
 * Closed set of model tasks the agent loop may dispatch.
 */
public enum AgentTaskType {
    GAP_ANALYSIS,
    PLAN_NEXT_ACTION,
    DRAFT_NODE,
    INTERPRET_ANSWER,
    DRAFT_ANSWER_PATCH,
    REFLECT_NODE,
    REFLECT_PATCH,
    DRAFT_SPEC,
    GROUND_SPEC;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentTaskType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent task type code must not be blank");
        }
        return AgentTaskType.valueOf(code.toUpperCase());
    }
}