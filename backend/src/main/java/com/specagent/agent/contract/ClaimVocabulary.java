package com.specagent.agent.contract;

import java.util.Set;

/**
 * Closed claim vocabulary shared by legacy structured output parsing and the
 * cross-language contract. Single authority so both sides reject the same
 * unknown values.
 */
public final class ClaimVocabulary {

    public static final Set<String> KINDS = Set.of(
            "goal", "stakeholder", "scope", "constraint", "success_criterion",
            "output_expectation", "risk", "assumption", "open_question", "conflict", "other");

    public static final Set<String> STATUSES = Set.of(
            "confirmed", "assumed", "unresolved", "rejected");

    private ClaimVocabulary() {
    }
}
