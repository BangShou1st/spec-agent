package com.specagent.agent.action;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.context.ContextSnapshot;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Verifies that an action proposal's base context still matches the current
 * authoritative graph state before execution. This prevents stale model
 * proposals from silently mutating a graph that has moved on.
 *
 * <p>Checks: context hash still matches, anchor node is still the route tip
 * (when applicable), and anchor refs still resolve to valid records.
 */
@Component
public class StaleContextChecker {

    private final RouteRepository routeRepository;

    public StaleContextChecker(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
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
}
