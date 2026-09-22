package com.specagent.agent.contract;

import java.util.List;
import java.util.UUID;
import com.specagent.retrieval.api.RetrievedContextItem;

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
                                 SkillCatalogView availableSkills,
                                 List<CapabilityResultView> capabilityResults,
                                 List<RelationView> relations,
                                 List<RelatedNodeRef> relatedNodes,
                                 List<RetrievedContextItem> retrievedContext,
                                 AutonomyInputs autonomy) {

    public AgentInputSnapshot {
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
        effectiveClaims = effectiveClaims == null ? List.of() : List.copyOf(effectiveClaims);
        allowedSourceRefs = allowedSourceRefs == null ? List.of() : List.copyOf(allowedSourceRefs);
        availableCapabilities = availableCapabilities == null
                ? List.of() : List.copyOf(availableCapabilities);
        availableSkills = availableSkills == null
                ? SkillCatalogView.empty() : availableSkills;
        capabilityResults = capabilityResults == null
                ? List.of() : List.copyOf(capabilityResults);
        relations = relations == null ? List.of() : List.copyOf(relations);
        relatedNodes = relatedNodes == null ? List.of() : List.copyOf(relatedNodes);
        retrievedContext = retrievedContext == null ? List.of() : List.copyOf(retrievedContext);
    }

    /** Legacy constructor for callers that predate the Skill catalog field. */
    public AgentInputSnapshot(String snapshotId,
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
                              List<RelationView> relations,
                              List<RelatedNodeRef> relatedNodes,
                              AutonomyInputs autonomy) {
        this(snapshotId, contextHash, projectId, routeId, anchorNodeId,
                routeContext, lineage, effectiveClaims, metadata, allowedSourceRefs,
                availableCapabilities, SkillCatalogView.empty(), capabilityResults,
                relations, relatedNodes, List.of(), autonomy);
    }

    /** Compatibility constructor for callers that already include Skills. */
    public AgentInputSnapshot(String snapshotId,
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
                              SkillCatalogView availableSkills,
                              List<CapabilityResultView> capabilityResults,
                              List<RelationView> relations,
                              List<RelatedNodeRef> relatedNodes,
                              AutonomyInputs autonomy) {
        this(snapshotId, contextHash, projectId, routeId, anchorNodeId, routeContext,
                lineage, effectiveClaims, metadata, allowedSourceRefs,
                availableCapabilities, availableSkills, capabilityResults,
                relations, relatedNodes, List.of(), autonomy);
    }
}
