package com.specagent.agent.action;

import java.util.List;

/**
 * Result of validating an action proposal against the current graph state.
 * Used by the policy engine and executor to decide whether to auto-execute,
 * require confirmation, or reject.
 */
public record ActionValidationResult(boolean accepted,
                                     List<String> errors,
                                     String riskLevel) {

    public static ActionValidationResult valid() {
        return new ActionValidationResult(true, List.of(), "NONE");
    }

    public static ActionValidationResult rejected(String... errors) {
        return new ActionValidationResult(false, List.of(errors), "HIGH");
    }

    public static ActionValidationResult rejected(List<String> errors) {
        return new ActionValidationResult(false, List.copyOf(errors), "HIGH");
    }
}
