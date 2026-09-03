package com.specagent.eval;

import com.specagent.agent.AgentRunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layer B-fast — deterministic evaluation profile (CI-blocking).
 *
 * <p>Judges the scripted Brain output at the boundary through the real
 * production flow: acceptable/forbidden primary action, required
 * properties, expected/forbidden state deltas, and the call budget.
 * Natural-language wording is never asserted — only the action family,
 * canonical state facts, and budgets.
 */
public final class LayerBFast {

    private LayerBFast() {
    }

    public static List<CheckResult> checkProperties(ScenarioDefinition scenario,
                                                    AttemptContext context) {
        List<CheckResult> results = new ArrayList<>();
        for (PropertyCheck check : scenario.expect().requiredProperties()) {
            results.add(evaluate(check, context));
        }
        return results;
    }

    public static List<Violation> checkActions(ScenarioDefinition scenario,
                                               AttemptContext context) {
        List<Violation> violations = new ArrayList<>();
        String action = context.actualPrimaryAction();
        if (!scenario.expect().acceptablePrimaryActions().isEmpty()
                && (action == null || !scenario.expect().acceptablePrimaryActions().contains(action))) {
            violations.add(new Violation(FailureClass.FORBIDDEN_ACTION,
                    "primary action " + action + " not in acceptable "
                            + scenario.expect().acceptablePrimaryActions()));
        }
        if (action != null && scenario.expect().forbiddenActions().contains(action)) {
            violations.add(new Violation(FailureClass.FORBIDDEN_ACTION,
                    "primary action " + action + " is forbidden"));
        }
        return violations;
    }

    public static List<Violation> checkStateDeltas(ScenarioDefinition scenario,
                                                   AttemptContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scenario.expect().expectedStateDeltas().entrySet()) {
            int actual = context.stateDelta().getOrDefault(entry.getKey(), 0);
            if (actual != entry.getValue()) {
                violations.add(new Violation(FailureClass.UNEXPECTED_STATE_DELTA,
                        "expected " + entry.getKey() + "=" + entry.getValue()
                                + " but observed " + actual));
            }
        }
        for (Map.Entry<String, Integer> entry : scenario.expect().forbiddenStateDeltas().entrySet()) {
            int actual = context.stateDelta().getOrDefault(entry.getKey(), 0);
            if (actual > entry.getValue()) {
                violations.add(new Violation(FailureClass.UNEXPECTED_STATE_DELTA,
                        "forbidden delta " + entry.getKey() + "=" + actual
                                + " exceeds max " + entry.getValue()));
            }
        }
        return violations;
    }

    private static CheckResult evaluate(PropertyCheck check, AttemptContext context) {
        return switch (check.property()) {
            case "ANSWER_PERSISTED" -> booleanCheck(check, deltaPositive(context, "answers"));
            case "PATCH_PERSISTED" -> booleanCheck(check, deltaPositive(context, "patches"));
            case "NO_DIVERGENCE" -> booleanCheck(check,
                    context.postAnswerIds().size() <= context.preAnswerIds().size() + 1
                            && context.failureDetail() == null);
            case "CONFLICT_SURFACED" -> booleanCheck(check,
                    "REQUEST_USER_INPUT".equals(context.actualPrimaryAction()));
            case "CONFIRMATION_REQUIRED" -> booleanCheck(check,
                    context.proposals().stream().anyMatch(
                            proposal -> "PROPOSED".equals(proposal.status().code()))
                            || (context.executionResult() != null
                                    && context.executionResult().startsWith("awaiting_approval")));
            case "NO_SIDE_EFFECT" -> booleanCheck(check,
                    context.capabilityInvocations().values().stream().mapToInt(Integer::intValue).sum()
                            == intArg(check, "maxCapabilityCalls", 0));
            case "RUN_COMPLETED" -> booleanCheck(check,
                    context.run() != null && context.run().status() == AgentRunStatus.COMPLETED);
            case "RUN_FAILED_CLOSED" -> booleanCheck(check,
                    context.run() != null && context.run().status() == AgentRunStatus.FAILED
                            && deltaZero(context, "answers", "patches", "nodes"));
            default -> new CheckResult(check.property(), false,
                    "unknown property: " + check.property());
        };
    }

    private static boolean deltaPositive(AttemptContext context, String key) {
        return context.stateDelta().getOrDefault(key, 0) > 0;
    }

    private static boolean deltaZero(AttemptContext context, String... keys) {
        for (String key : keys) {
            if (context.stateDelta().getOrDefault(key, 0) != 0) {
                return false;
            }
        }
        return true;
    }

    private static int intArg(PropertyCheck check, String key, int fallback) {
        Object value = check.args().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static CheckResult booleanCheck(PropertyCheck check, boolean holds) {
        return new CheckResult(check.property(), holds,
                holds ? "holds" : "missing: " + check.property() + " " + check.args());
    }
}
