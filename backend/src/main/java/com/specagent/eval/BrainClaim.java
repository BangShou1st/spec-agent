package com.specagent.eval;

import java.util.List;
import java.util.Map;

/** One STATE_UPDATE claim of the scripted Brain output (B-fast). */
public record BrainClaim(String kind, String textSeed, String status, Double confidence) {

    public String canonical() {
        return "claim(" + kind + "," + textSeed + "," + status + "," + confidence + ")";
    }
}
