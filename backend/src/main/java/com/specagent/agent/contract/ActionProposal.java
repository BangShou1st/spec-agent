package com.specagent.agent.contract;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single primary action proposal of one decision cycle. The brain only
 * proposes: base context identity must echo the request snapshot, source refs
 * must be a subset of the snapshot's allowed refs, and payloads never carry
 * runtime-owned ids. Java re-validates all of this fail-closed.
 */
public record ActionProposal(String actionFamily,
                             Map<String, Object> payload,
                             UUID baseContextSnapshotId,
                             String baseContextHash,
                             List<String> sourceRefs,
                             UUID proposalId,
                             String idempotencyKey,
                             List<String> anchorRefs) {

    public ActionProposal {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        anchorRefs = anchorRefs == null ? List.of() : List.copyOf(anchorRefs);
    }
}
