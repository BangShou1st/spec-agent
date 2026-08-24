package com.specagent.agent.action;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.context.ContextSnapshot;
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

    public StaleContextChecker(RouteRepository routeRepository,
                               NodeRepository nodeRepository) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
    }

    /**
     * Validates that the proposal's base context is still live. Throws
     * {@link StaleProposalException} when any check fails.
     */
    public void check(ActionProposal proposal, ActionExecutionContext context,
                      ContextSnapshot currentSnapshot) {
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
