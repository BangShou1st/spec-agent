package com.specagent.node;

/**
 * Knowledge state for claim-like node content. Not every kind uses this:
 * interaction and resource nodes carry no knowledge status.
 *
 * <p>This is deliberately distinct from operation/progress state, which lives
 * on {@code AgentRun}, and from route lifecycle status.
 */
public enum KnowledgeStatus {

    PROPOSED("PROPOSED"),
    CONFIRMED("CONFIRMED"),
    CHALLENGED("CHALLENGED"),
    SUPERSEDED("SUPERSEDED");

    private final String code;

    KnowledgeStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static KnowledgeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (KnowledgeStatus status : values()) {
            if (status.code.equals(code.trim().toUpperCase())) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown knowledge status: " + code);
    }
}
