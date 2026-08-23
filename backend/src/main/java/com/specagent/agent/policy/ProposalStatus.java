package com.specagent.agent.policy;

/**
 * Lifecycle states for an action proposal. Distinct from Node knowledge
 * state — proposals track the approval workflow, not content confidence.
 */
public enum ProposalStatus {
    PROPOSED,
    ACCEPTED,
    MODIFIED,
    REJECTED,
    EXPIRED;

    public String code() {
        return name();
    }

    public static ProposalStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Proposal status must not be blank");
        }
        return ProposalStatus.valueOf(code);
    }
}
