package com.specagent.agent.protocol;

import java.util.List;

/** The STATE_UPDATE part of a brain response. */
public record StateUpdateResult(List<ProposedClaim> claims) {

    public StateUpdateResult {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
