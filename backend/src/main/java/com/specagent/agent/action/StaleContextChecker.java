package com.specagent.agent.action;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.snapshot.AgentInputProjectionRepository;
import com.specagent.agent.snapshot.MutableSourceFingerprint;
import com.specagent.agent.snapshot.MutableSourceFingerprinter;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextRelation;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Verifies that an action proposal's frozen input still has valid live
 * preconditions before a durable mutation is executed.
 *
 * <p>Snapshot id/hash prove proposal-to-frozen-input identity. Mutable node
 * bodies/liveness and the bounded NODE_QUERY semantic-relation set are checked
 * independently against authoritative live state. Read-only responses remain
 * replayable from the original frozen payload even after workspace drift.
 */
@Component
public class StaleContextChecker {

    private static final Set<NodeRelationType> MODEL_VISIBLE_RELATION_TYPES = Set.of(
            NodeRelationType.RELATED_TO,
            NodeRelationType.DEPENDS_ON,
            NodeRelationType.DERIVED_FROM,
            NodeRelationType.CONFLICTS_WITH,
            NodeRelationType.SUPPORTS);

    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final NodeRelationRepository nodeRelationRepository;
    private final AgentInputProjectionRepository projectionRepository;
    private final MutableSourceFingerprinter fingerprinter;

    public StaleContextChecker(RouteRepository routeRepository,
                               NodeRepository nodeRepository,
                               NodeRelationRepository nodeRelationRepository,
                               AgentInputProjectionRepository projectionRepository,
                               MutableSourceFingerprinter fingerprinter) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.nodeRelationRepository = nodeRelationRepository;
        this.projectionRepository = projectionRepository;
        this.fingerprinter = fingerprinter;
    }

    /**
     * Validates that a mutating proposal still satisfies the live preconditions
     * of the exact frozen model input it was based on.
     */
    public void check(ActionProposal proposal, ActionExecutionContext context,
                      ContextSnapshot currentSnapshot) {
        if (isReadOnlyFamily(proposal)) {
            return;
        }
        if (!currentSnapshot.id().equals(proposal.baseContextSnapshotId())) {
            throw new StaleProposalException(
                    "Proposal baseContextSnapshotId does not match current snapshot");
        }
        if (!currentSnapshot.contextHash().equals(proposal.baseContextHash())) {
            throw new StaleProposalException(
                    "Proposal baseContextHash is stale: graph state has changed since the snapshot");
        }
        if (proposal.anchorRefs() != null && !proposal.anchorRefs().isEmpty()
                && context.routeId() != null) {
            Route route = routeRepository.findById(context.routeId()).orElse(null);
            if (route != null && route.tipNodeId() != null) {
                for (String anchorRef : proposal.anchorRefs()) {
                    if (anchorRef.startsWith("node:")) {
                        UUID anchorNodeId = UUID.fromString(anchorRef.substring(5));
                        if (!anchorNodeId.equals(route.tipNodeId())) {
                            throw new StaleProposalException(
                                    "Proposal anchor node is no longer the route tip");
                        }
                    }
                }
            }
        }
        verifyMutableSourcesStillFresh(currentSnapshot);
        verifyRelevantRelationsStillFresh(currentSnapshot);
    }

    private boolean isReadOnlyFamily(ActionProposal proposal) {
        try {
            ActionFamily family = ActionFamily.fromCode(proposal.actionFamily());
            return family == ActionFamily.RESPOND_TO_USER || family == ActionFamily.WAIT;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Verifies all model-visible mutable nodes. Current projection rows use the
     * persisted rich P1 fingerprints. Legacy projection rows whose fingerprint
     * column is empty derive the expected hashes only from their immutable
     * frozen payload; current live state is never used to reconstruct history.
     *
     * <p>Missing, cross-project, or retracted nodes are stale even if their
     * persisted body bytes would otherwise hash to the same value.
     */
    public void verifyMutableSourcesStillFresh(ContextSnapshot snapshot) {
        AgentInputProjectionRepository.FrozenInputProjection frozen =
                projectionRepository.findBySnapshotId(snapshot.id())
                        .orElseThrow(() -> new StaleProposalException(
                                "Proposal base context is stale: frozen input projection is unavailable"));

        Set<UUID> requiredIds = new HashSet<>(snapshot.includedNodeIds());
        requiredIds.addAll(snapshot.relatedNodeIds());

        Map<UUID, String> expectedById = new HashMap<>();
        Map<UUID, String> expectedType = new HashMap<>();
        boolean legacyDerived = frozen.sourceFingerprints().isEmpty();

        if (!legacyDerived) {
            for (MutableSourceFingerprint fingerprint : frozen.sourceFingerprints()) {
                expectedById.put(fingerprint.sourceId(), fingerprint.contentHash());
                expectedType.put(fingerprint.sourceId(), fingerprint.sourceType());
            }
        } else {
            deriveLegacyExpectedFingerprints(frozen, expectedById, expectedType);
        }

        if (!expectedById.keySet().containsAll(requiredIds)) {
            throw new StaleProposalException(
                    "Proposal base context is stale: frozen mutable-source preconditions are incomplete");
        }

        for (UUID id : requiredIds) {
            Node live = nodeRepository.findById(id)
                    .orElseThrow(() -> new StaleProposalException(
                            "Proposal base context is stale: model-visible node " + id + " no longer exists"));
            if (!snapshot.projectId().equals(live.projectId())) {
                throw new StaleProposalException(
                        "Proposal base context is stale: model-visible node " + id + " changed project ownership");
            }
            if (live.isRetracted()) {
                throw new StaleProposalException(
                        "Proposal base context is stale: model-visible node " + id + " was retracted");
            }
            String liveHash = legacyDerived
                    ? fingerprinter.modelVisibleNodeHash(live)
                    : fingerprinter.nodeBodyHash(live);
            if (!Objects.equals(expectedById.get(id), liveHash)) {
                throw new StaleProposalException(
                        "Proposal base context is stale: mutable source "
                                + expectedType.getOrDefault(id, "NODE") + " " + id
                                + " has changed since the model input was frozen");
            }
        }
    }

    private void deriveLegacyExpectedFingerprints(
            AgentInputProjectionRepository.FrozenInputProjection frozen,
            Map<UUID, String> expectedById,
            Map<UUID, String> expectedType) {
        AgentInputSnapshot projection;
        try {
            projection = AgentContracts.read(frozen.payload(), AgentInputSnapshot.class);
        } catch (RuntimeException ex) {
            throw new StaleProposalException(
                    "Proposal base context is stale: legacy frozen input preconditions cannot be derived");
        }
        projection.lineage().forEach(entry -> {
            if (entry.node() != null) {
                expectedById.put(entry.node().id(), fingerprinter.modelVisibleNodeHash(entry.node()));
                expectedType.put(entry.node().id(), "NODE");
            }
        });
        projection.relatedNodes().forEach(related -> {
            if (related.node() != null) {
                expectedById.put(related.node().id(), fingerprinter.modelVisibleNodeHash(related.node()));
                expectedType.put(related.node().id(), "RELATED_NODE");
            }
        });
    }

    /**
     * NODE_QUERY exposes one bounded hop of ACTIVE semantic relations touching
     * its anchor. Recompute exactly that same bounded set from live state and
     * compare the source/target/type facts. This catches both retraction/removal
     * and newly-added relevant relations without making unrelated project
     * relation changes stale.
     */
    private void verifyRelevantRelationsStillFresh(ContextSnapshot snapshot) {
        if (snapshot.operationType() != ContextOperationType.NODE_QUERY || snapshot.tipNodeId() == null) {
            return;
        }
        Set<RelationKey> frozenRelations = snapshot.relations().stream()
                .map(RelationKey::from)
                .collect(Collectors.toSet());
        Set<RelationKey> liveRelations = nodeRelationRepository
                .findActiveTouchingNode(snapshot.projectId(), snapshot.tipNodeId()).stream()
                .filter(relation -> MODEL_VISIBLE_RELATION_TYPES.contains(relation.relationType()))
                .map(RelationKey::from)
                .collect(Collectors.toSet());
        if (!frozenRelations.equals(liveRelations)) {
            throw new StaleProposalException(
                    "Proposal base context is stale: model-visible semantic relation set changed");
        }
    }

    private record RelationKey(UUID sourceNodeId, UUID targetNodeId, String relationType) {
        static RelationKey from(ContextRelation relation) {
            return new RelationKey(relation.sourceNodeId(), relation.targetNodeId(), relation.relationType());
        }

        static RelationKey from(NodeRelation relation) {
            return new RelationKey(relation.sourceNodeId(), relation.targetNodeId(), relation.relationType().code());
        }
    }

    /**
     * Deterministic live preconditions for a replacement commit, evaluated
     * AFTER the model returned but BEFORE any topology mutation.
     */
    public void verifyLiveExecutionPreconditions(UUID expectedSourceRouteId,
                                                 UUID expectedSourceRouteTip,
                                                 UUID targetNodeId) {
        Route route = routeRepository.findById(expectedSourceRouteId)
                .orElseThrow(() -> new StaleProposalException(
                        "Source route no longer exists: " + expectedSourceRouteId));
        if (route.tipNodeId() == null) {
            throw new StaleProposalException(
                    "Source route lost its tip before the replacement commit");
        }
        if (!Objects.equals(route.tipNodeId(), expectedSourceRouteTip)) {
            throw new StaleProposalException(
                    "Source route changed after the replacement snapshot: expected tip "
                            + expectedSourceRouteTip + ", current tip " + route.tipNodeId());
        }
        Set<UUID> seen = new HashSet<>();
        UUID current = route.tipNodeId();
        boolean containsTarget = false;
        while (current != null && seen.add(current)) {
            if (current.equals(targetNodeId)) {
                containsTarget = true;
                break;
            }
            Node node = nodeRepository.findById(current).orElse(null);
            current = node != null ? node.parentNodeId() : null;
        }
        if (!containsTarget) {
            throw new StaleProposalException(
                    "Replacement target is no longer on the source route lineage: " + targetNodeId);
        }
    }
}
