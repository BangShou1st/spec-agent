package com.specagent.agent.action;

import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.snapshot.AgentInputProjectionRepository;
import com.specagent.agent.snapshot.MutableSourceFingerprint;
import com.specagent.agent.snapshot.MutableSourceFingerprinter;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Verifies that an action proposal's base context still matches the current
 * frozen run snapshot before execution, plus deterministic live execution
 * preconditions for cycles that commit topology.
 *
 * <p>The {@code currentSnapshot} parameter of {@link #check} is the FROZEN
 * run snapshot the proposal was built against — not a rebuilt live snapshot —
 * so its hash/snapshot-id checks prove proposal↔snapshot identity only.
 * Live liveness is covered by {@link #verifyLiveExecutionPreconditions}
 * (expected source-route tip identity and target lineage membership), which
 * replacement commits must call before mutating anything.
 */
@Component
public class StaleContextChecker {

    private final RouteRepository routeRepository;

    private final NodeRepository nodeRepository;
    private final AgentInputProjectionRepository projectionRepository;
    private final MutableSourceFingerprinter fingerprinter;

    public StaleContextChecker(RouteRepository routeRepository,
                               NodeRepository nodeRepository,
                               AgentInputProjectionRepository projectionRepository,
                               MutableSourceFingerprinter fingerprinter) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.projectionRepository = projectionRepository;
        this.fingerprinter = fingerprinter;
    }

    /**
     * Validates that the proposal's base context is still live. Throws
     * {@link StaleProposalException} when any check fails.
     *
     * <p>Read-only families (RESPOND_TO_USER, WAIT) are not a live mutation
     * and are never gated by mutable-source staleness — they stay replayable
     * even when the workspace drifted. Graph-mutating families compare the
     * frozen mutable-source fingerprints (lineage + related node bodies) with
     * the current authoritative rows and reject any mismatch, so a stale
     * execution can never silently apply a decision taken on outdated body
     * content. Unrelated workspace changes that were not model-visible never
     * affect the gate.
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
        if (proposal.anchorRefs() != null && !proposal.anchorRefs().isEmpty()) {
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
     * Compares frozen fingerprints captured at freeze time with live node
     * bodies for exactly the same source identities. Any relevant mutation
     * yields STALE_CONTEXT and zero mutations.
     */
    public void verifyMutableSourcesStillFresh(ContextSnapshot snapshot) {
        AgentInputProjectionRepository.FrozenInputProjection frozen =
                projectionRepository.findBySnapshotId(snapshot.id()).orElse(null);
        if (frozen == null || frozen.sourceFingerprints().isEmpty()) {
            return;
        }
        java.util.Map<java.util.UUID, String> frozenById = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, String> frozenType = new java.util.HashMap<>();
        for (MutableSourceFingerprint f : frozen.sourceFingerprints()) {
            frozenById.put(f.sourceId(), f.contentHash());
            frozenType.put(f.sourceId(), f.sourceType());
        }
        // Live fingerprints for the same snapshot identities.
        java.util.List<Node> lineageNodes = snapshot.includedNodeIds().stream()
                .map(nodeRepository::findById)
                .map(opt -> opt.orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        java.util.Set<java.util.UUID> lineageIds = lineageNodes.stream().map(Node::id)
                .collect(java.util.stream.Collectors.toSet());
        java.util.List<Node> relatedNodes = new java.util.ArrayList<>();
        for (java.util.UUID relatedId : snapshot.relatedNodeIds()) {
            if (lineageIds.contains(relatedId)) {
                continue;
            }
            nodeRepository.findById(relatedId).ifPresent(relatedNodes::add);
        }
        java.util.List<MutableSourceFingerprint> live = fingerprinter.fingerprintsFor(lineageNodes, relatedNodes);
        java.util.Map<java.util.UUID, String> liveById = new java.util.HashMap<>();
        for (MutableSourceFingerprint f : live) {
            liveById.put(f.sourceId(), f.contentHash());
        }
        for (java.util.UUID id : frozenById.keySet()) {
            String frozenHash = frozenById.get(id);
            String liveHash = liveById.get(id);
            if (liveHash == null || !liveHash.equals(frozenHash)) {
                throw new StaleProposalException(
                        "Proposal base context is stale: mutable source " + frozenType.get(id)
                                + " " + id + " has changed since the model input was frozen");
            }
        }
    }

    /**
     * Deterministic live preconditions for a replacement commit, evaluated
     * AFTER the model returned but BEFORE any topology mutation. The model
     * decided on the frozen snapshot; if the source route changed since
     * (a new tip node was appended), lost its tip, or the target left the
     * source-route lineage, the decision is stale and must fail closed — even
     * though the target may still sit on some historical lineage.
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
        if (!java.util.Objects.equals(route.tipNodeId(), expectedSourceRouteTip)) {
            throw new StaleProposalException(
                    "Source route changed after the replacement snapshot: expected tip "
                            + expectedSourceRouteTip + ", current tip " + route.tipNodeId());
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
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
                    "Replacement target is no longer on the source route lineage: "
                            + targetNodeId);
        }
    }
}
