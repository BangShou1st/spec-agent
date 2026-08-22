package com.specagent.agent.runevent;

/**
 * Public run phases appended to {@code agent_run_events}. The UI progress
 * text must derive from these real phases, never from invented copy.
 */
public enum AgentRunPhase {
    CREATED,
    SNAPSHOT_BUILT,
    STATE_UPDATING,
    STATE_UPDATED,
    DECIDING,
    PROPOSAL_CREATED,
    AWAITING_APPROVAL,
    EXECUTING,
    WAITING_USER,
    COMPLETED,
    FAILED,
    STALE;

    public String code() {
        return name();
    }

    public static AgentRunPhase fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent run phase must not be blank");
        }
        return AgentRunPhase.valueOf(code);
    }
}
