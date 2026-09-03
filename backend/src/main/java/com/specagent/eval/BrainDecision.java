package com.specagent.eval;

import java.util.Map;

/** The scripted DECISION primary action of the Brain output (B-fast). */
public record BrainDecision(String actionFamily, Map<String, Object> payload) {

    public BrainDecision {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
