package com.specagent.agent.policy;

/**
 * Result of the Advisor policy engine's evaluation of an action proposal.
 * Determines whether the action may be auto-executed, requires user
 * confirmation, or is denied outright.
 *
 * <p>Confidence is never a factor in autoExecute or requiresConfirmation.
 * It is carried only as an informational signal.
 */
public record PolicyDecision(MutationClass classification,
                             boolean autoExecute,
                             boolean requiresConfirmation,
                             String denyReason) {

    public static PolicyDecision autoExecute(MutationClass classification) {
        return new PolicyDecision(classification, true, false, null);
    }

    public static PolicyDecision requireConfirmation(MutationClass classification) {
        return new PolicyDecision(classification, false, true, null);
    }

    public static PolicyDecision deny(MutationClass classification, String reason) {
        return new PolicyDecision(classification, false, false, reason);
    }
}
