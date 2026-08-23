package com.specagent.agent.policy;

import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.action.StaleProposalException;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.GraphOperation;
import com.specagent.graph.GraphOperationRepository;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes a user-accepted Advisor proposal.
 *
 * <p>Acceptance is the confirmation that Advisor mode required: the stored
 * proposal is re-validated against current graph facts (stale anchors and
 * vanished endpoints are rejected, never silently rebased), executed through
 * the runtime command layer, and recorded in the typed operation log as an
 * agent mutation traceable to the proposal.
 */
@Service
public class ProposalAcceptanceService {

    private final AgentProposalService proposalService;
    private final ActionExecutor actionExecutor;
    private final GraphCommandService graphCommandService;
    private final GraphOperationRepository operationRepository;
    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;

    public ProposalAcceptanceService(AgentProposalService proposalService,
                                     ActionExecutor actionExecutor,
                                     GraphCommandService graphCommandService,
                                     GraphOperationRepository operationRepository,
                                     NodeRepository nodeRepository,
                                     RouteRepository routeRepository) {
        this.proposalService = proposalService;
        this.actionExecutor = actionExecutor;
        this.graphCommandService = graphCommandService;
        this.operationRepository = operationRepository;
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
    }

    public record AcceptedProposalResult(String actionFamily, UUID producedNodeId, UUID relationId) {
    }

    /**
     * Accepts and executes a pending proposal in one transaction. Execution
     * failures leave the proposal PROPOSED so the user can retry after the
     * underlying problem is resolved.
     */
    @Transactional
    public AcceptedProposalResult acceptAndExecute(UUID proposalId, String decidedBy) {
        AgentProposal stored = proposalService.getProposal(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));
        if (stored.status() != ProposalStatus.PROPOSED) {
            throw new IllegalStateException(
                    "Proposal is not pending acceptance: " + proposalId + " is " + stored.status());
        }

        ActionProposal proposal = rebuildActionProposal(stored);
        validateStillFresh(proposal, stored);

        AcceptedProposalResult result = switch (stored.actionFamily()) {
            case "CREATE_NODE", "REQUEST_USER_INPUT" -> executeNodeAction(proposal, stored);
            case "CONNECT_NODE" -> executeConnectNode(proposal, stored);
            case "INVOKE_CAPABILITY" -> executeCapabilityInvocation(proposal, stored);
            // UPDATE_NODE / CREATE_ROUTE / GENERATE_ARTIFACT / CONTINUATION
            // connections never reach here as PROPOSED proposals: policy denies
            // them before creation because no command layer executes them in
            // this stage. The defensive failure below stays fail-closed.
            default -> throw new UnsupportedOperationException(
                    "Action family " + stored.actionFamily()
                            + " cannot be executed on acceptance in this stage");
        };

        proposalService.acceptProposal(proposalId, decidedBy);
        // Capability invocations produce no graph entity; node/relation
        // families always produce exactly one.
        List<UUID> producedRefs = result.producedNodeId() != null
                ? List.of(result.producedNodeId())
                : result.relationId() != null ? List.of(result.relationId()) : List.of();
        operationRepository.append(stored.projectId(), GraphOperation.Actor.AGENT,
                GraphOperation.Type.ACCEPT_AGENT_PROPOSAL,
                producedRefs,
                Map.of(), Map.of("actionFamily", stored.actionFamily()),
                "proposal:" + proposalId);
        return result;
    }

    /**
     * Rebuilds the wire-level proposal view from the persisted record. The
     * persisted payload is the already-validated model payload; identity
     * fields are restored from runtime-owned columns.
     */
    private ActionProposal rebuildActionProposal(AgentProposal stored) {
        return new ActionProposal(
                stored.actionFamily(),
                stored.payload(),
                stored.baseContextSnapshotId(),
                stored.baseContextHash(),
                List.of(),
                stored.id(),
                stored.idempotencyKey(),
                stored.anchorRefs());
    }

    /**
     * Stale validation against current graph facts. Node-creating proposals
     * require their anchor to still be the route tip; relation proposals
     * require both endpoints to still exist unretracted.
     */
    private void validateStillFresh(ActionProposal proposal, AgentProposal stored) {
        switch (stored.actionFamily()) {
            case "CREATE_NODE", "REQUEST_USER_INPUT" -> {
                Route route = routeRepository.findById(stored.routeId())
                        .orElseThrow(() -> new StaleProposalException(
                                "Proposal route no longer exists: " + stored.routeId()));
                UUID anchorNodeId = firstNodeRef(proposal);
                UUID requiredTip = anchorNodeId != null ? anchorNodeId : route.tipNodeId();
                if (requiredTip == null || !requiredTip.equals(route.tipNodeId())) {
                    throw new StaleProposalException(
                            "Proposal anchor is no longer the route tip; the graph has moved on. "
                                    + "Trigger a new decision instead of accepting this proposal.");
                }
            }
            case "CONNECT_NODE" -> {
                UUID sourceId = nodeRefFrom(stored.payload().get("sourceRef"));
                UUID targetId = nodeRefFrom(stored.payload().get("targetRef"));
                requireLiveNode(sourceId);
                requireLiveNode(targetId);
            }
            case "INVOKE_CAPABILITY" -> {
                // Capability arguments may reference graph nodes; those refs
                // must still be live at acceptance time, mirroring the wire
                // validation the proposal passed when it was created.
                if (stored.payload().get("arguments") instanceof Map<?, ?> arguments) {
                    for (Object value : arguments.values()) {
                        if (value instanceof String ref && ref.startsWith("node:")) {
                            requireLiveNode(UUID.fromString(ref.substring(5)));
                        }
                    }
                }
            }
            default -> {
                // No freshness rule for other families in this stage.
            }
        }
    }

    private AcceptedProposalResult executeNodeAction(ActionProposal proposal, AgentProposal stored) {
        UUID anchorNodeId = firstNodeRef(proposal);
        ActionExecutionContext context = new ActionExecutionContext(
                stored.runId(), stored.projectId(), stored.routeId(),
                stored.baseContextSnapshotId(), anchorNodeId, null, null);
        ActionResult result = actionExecutor.execute(proposal, context);
        return new AcceptedProposalResult(stored.actionFamily(), result.producedNodeId(), null);
    }

    private AcceptedProposalResult executeConnectNode(ActionProposal proposal, AgentProposal stored) {
        Object relationClass = stored.payload().get("relationClass");
        if (!"SEMANTIC".equals(relationClass)) {
            throw new UnsupportedOperationException(
                    "Only SEMANTIC relations are executable on acceptance; CONTINUATION "
                            + "connections must go through continuation commands");
        }
        NodeRelation relation = graphCommandService.createSemanticRelation(
                stored.projectId(),
                nodeRefFrom(stored.payload().get("sourceRef")),
                nodeRefFrom(stored.payload().get("targetRef")),
                NodeRelationType.fromCode((String) stored.payload().get("relationType")),
                NodeRelation.Origin.AGENT,
                stored.id(),
                stored.runId());
        return new AcceptedProposalResult(stored.actionFamily(), null, relation.id());
    }

    /**
     * Local-durable capability invocations execute through the same action
     * executor used by the auto-execute path (which routes into the
     * capability runtime with its runtime-owned idempotency key) — acceptance
     * never duplicates execution logic. Read-only capabilities never become
     * proposals (policy auto-executes them); external side-effect classes are
     * denied before creation.
     */
    private AcceptedProposalResult executeCapabilityInvocation(ActionProposal proposal,
                                                               AgentProposal stored) {
        UUID anchorNodeId = firstNodeRef(proposal);
        ActionExecutionContext context = new ActionExecutionContext(
                stored.runId(), stored.projectId(), stored.routeId(),
                stored.baseContextSnapshotId(), anchorNodeId, null, null);
        ActionResult result = actionExecutor.execute(proposal, context);
        return new AcceptedProposalResult(stored.actionFamily(), result.producedNodeId(), null);
    }

    private UUID firstNodeRef(ActionProposal proposal) {
        return proposal.anchorRefs().stream()
                .filter(ref -> ref.startsWith("node:"))
                .map(ref -> nodeRefFrom(ref))
                .findFirst()
                .orElse(null);
    }

    private UUID nodeRefFrom(Object ref) {
        if (!(ref instanceof String value) || !value.startsWith("node:")) {
            throw new StaleProposalException("Relation endpoint is not a node ref: " + ref);
        }
        return UUID.fromString(value.substring(5));
    }

    private void requireLiveNode(UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new StaleProposalException(
                        "Relation endpoint no longer exists: " + nodeId));
        if (node.isRetracted()) {
            throw new StaleProposalException(
                    "Relation endpoint has been retracted: " + nodeId);
        }
    }
}
