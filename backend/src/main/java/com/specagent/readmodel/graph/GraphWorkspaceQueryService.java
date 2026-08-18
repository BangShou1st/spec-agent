package com.specagent.readmodel.graph;

import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Same defensive depth bound used by the runtime lineage walk. */
    private static final int MAX_LINEAGE_DEPTH = 10_000;

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;
    private final AnswerService answerService;

    public GraphWorkspaceQueryService(ProjectService projectService,
                                      RouteService routeService,
                                      NodeService nodeService,
                                      AnswerService answerService) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
        this.answerService = answerService;
    }

    public GraphWorkspaceView getForProject(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

        Map<UUID, Node> nodesById = new LinkedHashMap<>();
        List<GraphWorkspaceRouteView> routeViews = new ArrayList<>();
        List<GraphWorkspaceAnswerView> answerViews = new ArrayList<>();

        for (Route route : routeService.listRoutes(projectId)) {
            List<Node> lineage = resolveLineage(project.id(), route);
            List<UUID> lineageNodeIds = lineage.stream().map(Node::id).toList();
            lineage.forEach(node -> nodesById.putIfAbsent(node.id(), node));
            answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)
                    .stream().map(GraphWorkspaceAnswerView::from).forEach(answerViews::add);
            routeViews.add(GraphWorkspaceRouteView.from(route, project.activeRouteId(), lineageNodeIds));
        }

        return new GraphWorkspaceView(
                project.id(), project.activeRouteId(), List.copyOf(routeViews),
                nodesById.values().stream().map(GraphWorkspaceNodeView::from).toList(),
                List.copyOf(answerViews));
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

        List<Node> fromTipToRoot = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID current = route.tipNodeId();

        while (current != null) {
            if (!visited.add(current)) {
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "Route lineage contains a cycle");
            }
            if (fromTipToRoot.size() >= MAX_LINEAGE_DEPTH) {
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "Route lineage exceeds maximum depth");
            }
            Node node = nodeService.getNode(current)
                    .orElseThrow(() -> GraphWorkspaceQueryException.of(
                            GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                            "A node in the route lineage does not resolve"));
            if (!node.projectId().equals(projectId)) {
                // Fail closed: neither the foreign node nor any node beyond it
                // may be exposed in the response.
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "A node in the route lineage belongs to another project");
            }
            fromTipToRoot.add(node);
            current = node.parentNodeId();
        }

        List<Node> rootToTip = new ArrayList<>();
        for (int i = fromTipToRoot.size() - 1; i >= 0; i--) {
            rootToTip.add(fromTipToRoot.get(i));
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
