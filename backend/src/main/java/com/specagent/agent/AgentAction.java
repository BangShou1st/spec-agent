package com.specagent.agent;

/**
 * Closed set of actions the agent loop may produce.
 *
 * <p>The action enum is deliberately closed: the agent may never emit an
 * arbitrary string action, and route lifecycle operations are not part of it.
 * Route lifecycle stays under runtime service control.
 */
public enum AgentAction {
    ASK_NEXT_QUESTION,
    INTERPRET_ANSWER,
    REQUEST_CONFIRMATION,
    EXPLAIN_CONFLICT,
    SUGGEST_BRANCH,
    GENERATE_SPEC,
    STOP;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentAction fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent action code must not be blank");
        }
        return AgentAction.valueOf(code.toUpperCase());
    }
}