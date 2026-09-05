package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionFamily;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unknown action eligibility version: " + version);
        }
        Set<String> declaredFamilies = new HashSet<>();
        for (String family : eligibleFamilies) {
            ActionFamily.fromCode(family);
            if (!declaredFamilies.add(family)) {
                throw new IllegalArgumentException("Duplicate eligible action family: " + family);
            }
        }
        Set<String> expected = java.util.Arrays.stream(ActionFamily.values())
                .map(ActionFamily::code)
                .collect(java.util.stream.Collectors.toSet());
        if (!constraints.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "Eligibility constraints must cover the complete action family set");
        }
        for (String family : expected) {
            boolean listed = declaredFamilies.contains(family);
            if (constraints.get(family).eligible() != listed) {
                throw new IllegalArgumentException(
                        "Eligibility list/constraint mismatch for family: " + family);
            }
        }
        if (basisHash == null || !basisHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Eligibility basisHash must be a SHA-256 hex digest");
        }
    }
}
