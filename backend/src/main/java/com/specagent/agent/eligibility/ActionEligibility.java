package com.specagent.agent.eligibility;

import java.util.List;
import java.util.Map;

/** Runtime-derived action eligibility mask for one event and frozen snapshot. */
public record ActionEligibility(
        String version,
        List<String> eligibleFamilies,
        Map<String, ActionEligibilityConstraint> constraints,
        String basisHash) {

    public static final String VERSION = "action-eligibility.v1";

    public ActionEligibility {
        eligibleFamilies = eligibleFamilies == null ? List.of() : List.copyOf(eligibleFamilies);
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }
}
