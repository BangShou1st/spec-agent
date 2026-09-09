package com.specagent.globalassistant.conversation;

/**
 * Minimal Global Assistant run lifecycle (frozen contract).
 *
 * <p>CREATED/RUNNING are active; COMPLETED/FAILED/CANCELLED are terminal.
 * {@code cancel_requested_at} is a cooperative signal column, never a status.
 * No CANCELLING/WAITING/USER_INPUT_REQUIRED persisted statuses exist:
 * clarification ends the current run normally via terminal state.
 */
public enum GlobalAssistantRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String code() {
        return name();
    }

    public static GlobalAssistantRunStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Run status must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }

    public boolean isActive() {
        return this == CREATED || this == RUNNING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
