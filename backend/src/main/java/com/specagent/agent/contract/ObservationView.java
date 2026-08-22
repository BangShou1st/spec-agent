package com.specagent.agent.contract;

import java.util.List;

/**
 * Structured observation derived by the decision engine. Interpretation for
 * reasoning only — it is not durable truth and never bypasses the
 * deterministic Java validators.
 */
public record ObservationView(List<String> known,
                              List<String> unknowns,
                              List<String> conflicts,
                              List<String> risks) {

    public ObservationView {
        known = known == null ? List.of() : List.copyOf(known);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
