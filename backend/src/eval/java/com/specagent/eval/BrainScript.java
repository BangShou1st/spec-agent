package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/** Scripted deterministic Brain output replacing the production model at the boundary (B-fast). */
public record BrainScript(
        List<BrainClaim> claims,
        BrainDecision decision,
        List<String> knownObservations,
        List<String> conflictObservations) {

    public BrainScript {
        claims = claims == null ? List.of() : List.copyOf(claims);
        knownObservations = knownObservations == null ? List.of() : List.copyOf(knownObservations);
        conflictObservations =
                conflictObservations == null ? List.of() : List.copyOf(conflictObservations);
    }

    public String canonical() {
        List<String> renderedClaims = new ArrayList<>();
        for (BrainClaim claim : claims) {
            renderedClaims.add(claim.canonical());
        }
        renderedClaims.sort(String::compareTo);
        return "brain" + renderedClaims
                + "[decision(" + decision.actionFamily() + ")"
                + ",known(" + knownObservations.size()
                + "),conflicts(" + conflictObservations.size() + ")]";
    }
}
