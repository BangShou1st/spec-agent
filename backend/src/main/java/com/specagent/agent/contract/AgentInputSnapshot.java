package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;

/**
 * The deterministic, runtime-built, frozen model-facing projection of one
 * decision cycle ({@code AgentInputSnapshot}).
 *
 * <p>All identities are runtime-owned. The projection is built Java-side from
 * the durable {@code ContextSnapshot} manifest; the brain never reconstructs
 * this state from database access. See {@code contracts/README.md} for the
 * frozen wire shape.
 */
public record AgentInputSnapshot(String snapshotId,
                                 String contextHash,
                                 UUID projectId,
                                 UUID routeId,
                                 UUID anchorNodeId,
                                 RouteContextView routeContext,
                                 List<LineageEntry> lineage,
                                 List<ClaimView> effectiveClaims,
                                 SnapshotMetadata metadata,
                                 List<String> allowedSourceRefs,
                                 List<CapabilityDescriptor> availableCapabilities,
                                 List<CapabilityResultView> capabilityResults,
                                 AutonomyInputs autonomy) {

    public AgentInputSnapshot {
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
        effectiveClaims = effectiveClaims == null ? List.of() : List.copyOf(effectiveClaims);
        allowedSourceRefs = allowedSourceRefs == null ? List.of() : List.copyOf(allowedSourceRefs);
        availableCapabilities = availableCapabilities == null
                ? List.of() : List.copyOf(availableCapabilities);
        capabilityResults = capabilityResults == null
                ? List.of() : List.copyOf(capabilityResults);
    }
}
