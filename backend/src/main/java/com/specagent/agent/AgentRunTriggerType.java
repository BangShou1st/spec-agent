package com.specagent.agent;

/**
 * Trigger that initiated an agent run.
 *
 * <p>These are operation mechanics, not business domains. They describe what
 * user operation caused the run, keeping the runtime domain-neutral.
 */
public enum AgentRunTriggerType {
    INITIAL_REQUIREMENT,
    ANSWER_NODE,
    REGENERATE_NODE,
    FORK_NODE,
    RESTORE_ROUTE,
    ARCHIVE_ROUTE,
    DELETE_ROUTE,
    GENERATE_SPEC,
    DECISION_CYCLE;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentRunTriggerType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Agent run trigger type code must not be null");
        }
        return AgentRunTriggerType.valueOf(code.toUpperCase());
    }
}
