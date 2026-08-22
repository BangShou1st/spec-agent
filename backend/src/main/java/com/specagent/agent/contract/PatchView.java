package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;

/** A persisted answer patch as seen by the decision engine. */
public record PatchView(UUID id, List<ClaimView> claims) {

    public PatchView {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
