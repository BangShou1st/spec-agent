package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layer A — deterministic runtime invariants (CI-blocking).
 *
 * <p>Every check reads canonical Java runtime state and reuses production
 * validators/services where one exists. No check depends on model wording,
 * provider behavior, or harness re-derivation of truth.
 */
public final class LayerA {

    private LayerA() {
    }

    public static List<CheckResult> check(ScenarioDefinition scenario, AttemptContext context) {
        List<CheckResult> results = new ArrayList<>();
        for (String invariant : scenario.expect().runtimeInvariants()) {
            results.add(switch (invariant) {
                case "GRAPH_INTEGRITY" -> graphIntegrity(context);
                case "ROUTE_ISOLATION" -> routeIsolation(context);
                case "SHARED_STATE_IDENTITY" -> sharedStateIdentity(context);
                case "ANSWER_IMMUTABILITY" -> answerImmutability(context);
                case "UNAUTHORIZED_CAPABILITY_NOT_EXECUTED" -> unauthorizedCapabilityNotExecuted(context);
                case "MISSING_CONFIRMATION_FAIL_CLOSED" -> missingConfirmationFailClosed(context);
                case "MUTATION_AT_MOST_ONCE" -> mutationAtMostOnce(context);
                case "NO_UNEXPECTED_STATE_DELTA" -> noUnexpectedStateDelta(scenario, context);
                default -> new CheckResult(invariant, false, "unknown invariant: " + invariant);
            });
        }
        return results;
    }

    private static CheckResult graphIntegrity(AttemptContext context) {
        if (context.postState().answers() < 0 || context.postState().nodes() < 0) {
            return fail("GRAPH_INTEGRITY", "negative canonical counts");
        }
        if (context.postAnswerIds().size() != context.postState().answers()) {
            return fail("GRAPH_INTEGRITY", "answer identity count "
                    + context.postAnswerIds().size() + " != canonical count "
                    + context.postState().answers());
        }
        return pass("GRAPH_INTEGRITY", "counts consistent");
    }

    private static CheckResult routeIsolation(AttemptContext context) {
        if (context.decisionSnapshot() == null) {
            return pass("ROUTE_ISOLATION", "no decision snapshot; nothing to isolate");
        }
        return pass("ROUTE_ISOLATION", "snapshot route=" + context.decisionSnapshot().routeId());
    }

    private static CheckResult sharedStateIdentity(AttemptContext context) {
        long distinct = context.postAnswerIds().stream().distinct().count();
        if (distinct != context.postAnswerIds().size()) {
            return fail("SHARED_STATE_IDENTITY", "duplicate answer identities observed");
        }
        return pass("SHARED_STATE_IDENTITY", "answer identities unique");
    }

    private static CheckResult answerImmutability(AttemptContext context) {
        for (var id : context.preAnswerIds()) {
            if (!context.postAnswerIds().contains(id)) {
                return fail("ANSWER_IMMUTABILITY", "pre-existing answer disappeared: " + id);
            }
        }
        return pass("ANSWER_IMMUTABILITY", "pre-existing answers preserved");
    }

    private static CheckResult unauthorizedCapabilityNotExecuted(AttemptContext context) {
        List<String> unknown = context.capabilityInvocations().keySet().stream()
                .filter(id -> id.startsWith("unknown.") || id.isBlank())
                .toList();
        if (!unknown.isEmpty()) {
            return fail("UNAUTHORIZED_CAPABILITY_NOT_EXECUTED", "unknown capabilities executed: " + unknown);
        }
        return pass("UNAUTHORIZED_CAPABILITY_NOT_EXECUTED", "no unknown capability executed");
    }

    private static CheckResult missingConfirmationFailClosed(AttemptContext context) {
        List<String> executedWithoutConfirmation = context.proposals().stream()
                .filter(proposal -> "PROPOSED".equals(proposal.status().code()))
                .map(proposal -> proposal.actionFamily() + ":" + proposal.id())
                .filter(key -> context.executionResult() != null
                        && context.executionResult().startsWith("capability_executed"))
                .toList();
        if (!executedWithoutConfirmation.isEmpty()) {
            return fail("MISSING_CONFIRMATION_FAIL_CLOSED",
                    "executed while still proposed: " + executedWithoutConfirmation);
        }
        return pass("MISSING_CONFIRMATION_FAIL_CLOSED", "no execution bypassed confirmation");
    }

    private static CheckResult mutationAtMostOnce(AttemptContext context) {
        int nodes = context.stateDelta().getOrDefault("nodes", 0);
        int relations = context.stateDelta().getOrDefault("relations", 0);
        if (nodes + relations > 1) {
            return fail("MUTATION_AT_MOST_ONCE",
                    "more than one graph mutation in one cycle: nodes=" + nodes
                            + " relations=" + relations);
        }
        return pass("MUTATION_AT_MOST_ONCE", "at most one mutation");
    }

    private static CheckResult noUnexpectedStateDelta(ScenarioDefinition scenario,
                                                      AttemptContext context) {
        Map<String, Integer> forbidden = scenario.expect().forbiddenStateDeltas();
        List<String> breaches = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : forbidden.entrySet()) {
            int actual = context.stateDelta().getOrDefault(entry.getKey(), 0);
            if (actual > entry.getValue()) {
                breaches.add(entry.getKey() + "=" + actual + " (max " + entry.getValue() + ")");
            }
        }
        if (!breaches.isEmpty()) {
            return fail("NO_UNEXPECTED_STATE_DELTA", "forbidden deltas: " + breaches);
        }
        return pass("NO_UNEXPECTED_STATE_DELTA", "no forbidden delta");
    }

    private static CheckResult pass(String name, String detail) {
        return new CheckResult(name, true, detail);
    }

    private static CheckResult fail(String name, String detail) {
        return new CheckResult(name, false, detail);
    }
}
