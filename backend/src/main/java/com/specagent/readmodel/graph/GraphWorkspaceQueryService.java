package com.specagent.readmodel.graph;

import com.specagent.answer.AnswerService;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteService;
import com.specagent.readmodel.lineage.ReadModelLineageWalker;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin read-model bridge that composes existing runtime reads into one
 * canonical project graph for display.
 *
 * <p>It never writes state, never calls a model, never builds or persists a
 * {@code ContextSnapshot}, and never re-implements route or context semantics.
 * It is not a second Runtime Kernel.
 *
 * <p>For every route, the lineage is walked from {@code tipNodeId} up through
 * {@code parentNodeId} pointers to the root and exposed in root→tip order.
 * The read is permitted for every lifecycle status (open, superseded,
 * archived, deleted). It fails closed as an internal invariant violation when
 * a node is missing, when a node belongs to another project, when the lineage
 * cycles or exceeds a maximum depth, or when the route's recorded root node
 * does not match the resolved lineage. A route without a tip node yields an
 * empty lineage.
 *
 * <p>Nodes are deduplicated across routes (shared nodes appear once).
 * Route-specific answers remain separate and are never merged by node.
 * Replacement routes naturally expose their parent lineage plus the
 * replacement node; a superseded target node is never injected into the
 * replacement route's lineage merely because {@code supersedesNodeId} points
 * at it.
 */
@Service
public class GraphWorkspaceQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;
    private final AnswerService answerService;
    private final RouteHistoryResolver routeHistoryResolver;
    private final NodeRelationRepository relationRepository;

    public GraphWorkspaceQueryService(ProjectService projectService,
                                      RouteService routeService,
                                      NodeService nodeService,
                                      AnswerService answerService,
                                      RouteHistoryResolver routeHistoryResolver,
                                      NodeRelationRepository relationRepository) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.relationRepository = relationRepository;
    }

    public GraphWorkspaceView getForProject(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

        Map<UUID, Node> nodesById = new LinkedHashMap<>();
        List<GraphWorkspaceRouteView> routeViews = new ArrayList<>();
        List<GraphWorkspaceAnswerView> answerViews = new ArrayList<>();
        // Shared-state divergence detector: one canonical Question Node may
        // carry only ONE immutable Answer identity project-wide. If the read
        // model ever sees two distinct effective Answer ids for the same node,
        // that is an invariant violation, not a normal UI presentation mode.
        java.util.Map<UUID, UUID> answerIdentityByNode = new java.util.HashMap<>();

        for (Route route : routeService.listRoutes(projectId)) {
            List<Node> lineage = resolveLineage(project.id(), route);
            List<UUID> lineageNodeIds = lineage.stream().map(Node::id).toList();
            lineage.forEach(node -> nodesById.putIfAbsent(node.id(), node));
            List<com.specagent.answer.Answer> answers = routeHistoryResolver == null
                    ? answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)
                    : routeHistoryResolver.resolveEffectiveAnswers(route.id(), lineageNodeIds);
            for (com.specagent.answer.Answer answer : answers) {
                UUID existing = answerIdentityByNode.putIfAbsent(answer.nodeId(), answer.id());
                if (existing != null && !existing.equals(answer.id())) {
                    throw GraphWorkspaceQueryException.of(
                            GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                            "SHARED_STATE_DIVERGENCE: canonical Question " + answer.nodeId()
                                    + " resolves to multiple effective Answer identities");
                }
                answerViews.add(GraphWorkspaceAnswerView.from(
                        answer, route.id(), !route.id().equals(answer.routeId())));
            }
            routeViews.add(GraphWorkspaceRouteView.from(route, project.activeRouteId(), lineageNodeIds));
        }

        // Floating drafts belong to no route lineage; they are still visible
        // standalone graph content and must reach the workspace view.
        nodeService.listProject(project.id()).stream()
                .filter(node -> !node.isRetracted())
                .forEach(node -> nodesById.putIfAbsent(node.id(), node));

        return new GraphWorkspaceView(
                project.id(), project.activeRouteId(), List.copyOf(routeViews),
                nodesById.values().stream()
                        .filter(node -> !node.isRetracted())
                        .map(GraphWorkspaceNodeView::from).toList(),
                List.copyOf(answerViews),
                relationRepository.findActiveByProject(projectId).stream()
                        .map(GraphWorkspaceRelationView::from).toList());
    }

    private List<Node> resolveLineage(UUID projectId, Route route) {
        if (route.tipNodeId() == null) {
            if (route.rootNodeId() != null) {
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "Route has a null tip but a non-null root node");
            }
            return List.of();
        }

        List<Node> rootToTip;
        try {
            rootToTip = ReadModelLineageWalker.walk(route.tipNodeId(), nodeService::getNode);
        } catch (ReadModelLineageWalker.LineageTraversalException ex) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION, ex.getMessage());
        }

        for (Node node : rootToTip) {
            if (!node.projectId().equals(projectId)) {
                // Fail closed: neither the foreign node nor any node beyond it
                // may be exposed in the response.
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "A node in the route lineage belongs to another project");
            }
        }

        if (route.rootNodeId() == null) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                    "Route has a tip node but no root node");
        }
        if (!route.rootNodeId().equals(rootToTip.get(0).id())) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                    "Route root node does not match the resolved lineage");
        }
        return List.copyOf(rootToTip);
    }
}
