package com.specagent.agent.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent record of an action proposal's lifecycle. Tracks the proposal
 * from PROPOSED through ACCEPTED/MODIFIED/REJECTED/EXPIRED, making every
 * decision traceable and auditable. Anchor refs are persisted so acceptance
 * can re-validate staleness against current graph facts.
 */
public record AgentProposal(UUID id,
                            UUID runId,
                            UUID projectId,
                            UUID routeId,
                            String actionFamily,
                            Map<String, Object> payload,
                            List<String> anchorRefs,
                            ProposalStatus status,
                            UUID baseContextSnapshotId,
                            String baseContextHash,
                            String idempotencyKey,
                            Instant createdAt,
                            Instant decidedAt,
                            String decidedBy) {

    public AgentProposal {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        anchorRefs = anchorRefs == null ? List.of() : List.copyOf(anchorRefs);
    }
}
