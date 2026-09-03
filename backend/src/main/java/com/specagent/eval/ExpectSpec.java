package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@code expect} block of one scenario: runtime invariants, required
 * properties, acceptable/forbidden primary actions, expected/forbidden
 * state deltas, and the call budget.
 */
public record ExpectSpec(
        Set<String> runtimeInvariants,
        List<PropertyCheck> requiredProperties,
        Set<String> acceptablePrimaryActions,
        Set<String> forbiddenActions,
        Map<String, Integer> expectedStateDeltas,
        Map<String, Integer> forbiddenStateDeltas,
        CallBudget callBudget) {

    public ExpectSpec {
        runtimeInvariants = runtimeInvariants == null ? Set.of() : Set.copyOf(runtimeInvariants);
        requiredProperties = requiredProperties == null ? List.of() : List.copyOf(requiredProperties);
        acceptablePrimaryActions =
                acceptablePrimaryActions == null ? Set.of() : Set.copyOf(acceptablePrimaryActions);
        forbiddenActions = forbiddenActions == null ? Set.of() : Set.copyOf(forbiddenActions);
        expectedStateDeltas = expectedStateDeltas == null ? Map.of() : Map.copyOf(expectedStateDeltas);
        forbiddenStateDeltas =
                forbiddenStateDeltas == null ? Map.of() : Map.copyOf(forbiddenStateDeltas);
    }

    public String canonical() {
        List<String> props = new ArrayList<>();
        for (PropertyCheck check : requiredProperties) {
            props.add(check.canonical());
        }
        props.sort(String::compareTo);
        return "expect[invariants(" + new TreeSet<>(runtimeInvariants) + ");"
                + "props(" + props + ");"
                + "accept(" + new TreeSet<>(acceptablePrimaryActions) + ");"
                + "forbid(" + new TreeSet<>(forbiddenActions) + ");"
                + "delta(" + new TreeMap<>(expectedStateDeltas) + ");"
                + "nodelta(" + new TreeMap<>(forbiddenStateDeltas) + ");"
                + "budget(" + callBudget.expectedStages() + ","
                + callBudget.minProductionCalls() + "," + callBudget.maxProductionCalls() + ","
                + callBudget.maxProviderRetries() + "," + callBudget.maxCapabilityCalls() + ")]";
    }
}
