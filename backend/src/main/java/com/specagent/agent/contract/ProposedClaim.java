package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;

/**
 * One brain-proposed claim. Carries content, status and the source refs that
 * ground it; never a runtime-owned claim id.
 */
public record ProposedClaim(String kind,
                            String text,
                            String status,
                            Double confidence,
                            List<String> sourceRefs) {

    public ProposedClaim {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
