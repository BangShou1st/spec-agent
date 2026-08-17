package com.specagent.agent;

/**
 * Lifecycle status of a single controlled agent execution.
 */
public enum AgentRunStatus {
    CREATED,
    CONTEXT_BUILT,
    MODEL_CALLED,
    REFLECTED,
    PERSISTED,
    COMPLETED,
    FAILED;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentRunStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Agent run status code must not be null");
        }
        return AgentRunStatus.valueOf(code.toUpperCase());
    }
}
