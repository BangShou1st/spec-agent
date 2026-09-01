package com.specagent.agent.action;

import com.specagent.graph.GraphInvariantValidator;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow transactional boundary for agent-driven graph mutations.
 *
 * <p>The model/policy chain (a Decision or Answer cycle) runs OUTSIDE any
 * transaction or lock. Only after the model and policy have completed does the
 * mutation enter this bean, which re-validates the decision against CURRENT
 * graph facts inside one transaction:
 *
 * <ol>
 *   <li>{@code projectRepository.lockById} — the project row lock that every
 *       project-wide graph writer takes first (order: project → route/node →
 *       graph write), so an auto-execute can never interleave with a
 *       concurrent Undo, continuation, or archive into a half-applied tip.</li>
 *   <li>The exact route is re-read and verified to belong to the project and
 *       to be {@code OPEN}.</li>
 *   <li>The expected anchor (the tip the model decided against) is re-checked
 *       against the CURRENT route tip: a null anchor requires a still-empty
 *       route, a non-null anchor must still be the tip. A moved-on route fails
 *       closed with {@link StaleProposalException} instead of silently
 *       rebasing onto newer state — a stale auto-execute must never overwrite
 *       a newer tip.</li>
 *   <li>The normal lineage/question invariants are validated.</li>
 *   <li>The node is created and the route tip/root advanced in the SAME
 *       transaction.</li>
 * </ol>
 *
 * <p>Normal auto-execute enters here through {@link ProposalActionExecutor};
 * accepted proposals join the same bean through the acceptance transaction
 * ({@code REQUIRED} propagation). Read-only actuator families
 * (RESPOND_TO_USER / WAIT / capability invocations) never enter this bean and
 * stay outside the graph lock.
 */
@Service
public class AgentGraphMutationService {

    /** The node creation to apply inside the transactional boundary. */
    public sealed interface NodeCreation permits InteractionNode, WorkspaceNode {
    }

    /** An INTERACTION question node (REQUEST_USER_INPUT / CREATE_NODE question). */
    public record InteractionNode(String questionText,
                                  String purpose,
                                  List<NodeOption> options,
                                  boolean allowFreeAnswer) implements NodeCreation {
    }

    /** A generic workspace node (CREATE_NODE with a non-INTERACTION kind). */
    public record WorkspaceNode(NodeKind kind,
                                String subtype,
                                Map<String, Object> content) implements NodeCreation {
    }

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final NodeService nodeService;
    private final GraphInvariantValidator invariantValidator;

    public AgentGraphMutationService(ProjectRepository projectRepository,
                                     RouteRepository routeRepository,
                                     NodeService nodeService,
                                     GraphInvariantValidator invariantValidator) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeService = nodeService;
        this.invariantValidator = invariantValidator;
    }

    /**
     * Applies one agent node creation atomically. {@code expectedTipNodeId} is
     * the anchor the model decided against (the route tip at decision time, or
     * null for an empty-route root). Node insert and route tip/root advancement
     * commit together.
     */
    @Transactional
    public Node executeNodeCreation(UUID projectId,
                                    UUID routeId,
                                    UUID expectedTipNodeId,
                                    NodeCreation creation) {
        // Lock order: project -> route/node -> graph write.
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        verifyAnchorIsCurrentTip(route, expectedTipNodeId);

        if (expectedTipNodeId == null) {
            // Empty-route root: the anchor was null and the route still has no
            // tip, so the new node becomes both root and tip.
            return switch (creation) {
                case InteractionNode node -> nodeService.createRootNode(
                        projectId, routeId, node.questionText(), node.purpose(),
                        node.options(), node.allowFreeAnswer());
                case WorkspaceNode node -> nodeService.createWorkspaceNode(
                        projectId, routeId, null, node.kind(), node.subtype(),
                        node.content(), NodeAuthorKind.AGENT, KnowledgeStatus.PROPOSED);
            };
        }

        // Append at the verified current tip. The anchor must still be a live
        // project node. Workspace-node continuation mirrors the user
        // continuation invariant (an unanswered Question stays a route tip);
        // the agent question-draft chain is intentionally exempt — drafting a
        // follow-up question is the approved question-chain behavior.
        requireNodeInProject(projectId, expectedTipNodeId);
        if (creation instanceof WorkspaceNode) {
            invariantValidator.validateQuestionCanHaveChild(projectId, routeId, expectedTipNodeId);
        }
        return switch (creation) {
            case InteractionNode node -> nodeService.createChildNode(
                    projectId, routeId, expectedTipNodeId, node.questionText(),
                    node.purpose(), node.options(), node.allowFreeAnswer());
            case WorkspaceNode node -> nodeService.createWorkspaceNode(
                    projectId, routeId, expectedTipNodeId, node.kind(), node.subtype(),
                    node.content(), NodeAuthorKind.AGENT, KnowledgeStatus.PROPOSED);
        };
    }

    /**
     * The anchor the model decided against must still describe the CURRENT
     * route: an empty route requires a null anchor, an append requires the
     * anchor to be the live tip. Anything else means the graph moved on under
     * the queued decision — fail closed, never rebase onto newer state.
     */
    private void verifyAnchorIsCurrentTip(Route route, UUID expectedTipNodeId) {
        if (expectedTipNodeId == null) {
            if (route.tipNodeId() != null) {
                throw new StaleProposalException(
                        "Auto-execute anchor is null but route " + route.id()
                                + " already has tip " + route.tipNodeId()
                                + "; the graph has moved on (empty-route root requires a still-empty route)");
            }
            return;
        }
        if (!expectedTipNodeId.equals(route.tipNodeId())) {
            throw new StaleProposalException(
                    "Auto-execute anchor " + expectedTipNodeId + " is no longer the route tip "
                            + route.tipNodeId() + "; the graph has moved on");
        }
    }

    private Route requireOpenRouteInProject(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route " + routeId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Auto-execute target route is not open: " + route.lifecycleStatus().code());
        }
        return route;
    }

    private void requireNodeInProject(UUID projectId, UUID nodeId) {
        Node node = nodeService.getNode(nodeId)
                .orElseThrow(() -> new StaleProposalException(
                        "Auto-execute anchor node no longer exists: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new StaleProposalException(
                    "Auto-execute anchor node does not belong to project: " + nodeId);
        }
    }
}