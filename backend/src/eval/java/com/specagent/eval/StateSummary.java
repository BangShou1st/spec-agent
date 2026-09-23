package com.specagent.eval;

/** Coarse canonical-state counts of one snapshot boundary (pre/post). */
public record StateSummary(
        int routes,
        int nodes,
        int answers,
        int patches,
        int proposals,
        int capabilityInvocations) {

    public static StateSummary of(int routes, int nodes, int answers,
                                  int patches, int proposals, int capabilityInvocations) {
        return new StateSummary(routes, nodes, answers, patches, proposals, capabilityInvocations);
    }

    public static StateSummary empty() {
        return new StateSummary(0, 0, 0, 0, 0, 0);
    }
}
