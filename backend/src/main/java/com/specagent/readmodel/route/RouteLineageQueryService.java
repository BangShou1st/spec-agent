package com.specagent.readmodel.route;

import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.readmodel.lineage.ReadModelLineageWalker;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Thin read-model bridge that resolves one route's historical node lineage for
 * display.
 *
 * <p>This is the only UI-support read bridge added for the route workspace: it
 * composes existing runtime reads and never writes state, never calls a model,
 * never builds or persists a {@code ContextSnapshot}, and never re-implements
 * route or context semantics. It is not a second Runtime Kernel.
 *
 * <p>Lineage semantics: the chain is walked from {@code tipNodeId} up through
 * {@code parentNodeId} pointers to the root and returned in root→tip order.
 * The read is permitted for every lifecycle status (open, superseded,
 * archived, deleted). It fails closed as an internal invariant violation when
 * a node is missing, when a node belongs to another project, when the lineage
 * cycles or exceeds a maximum depth, or when the route's recorded root node
 * does not match the resolved lineage. A route without a tip node yields an
 * empty node list.
 *
 * <p>Replacement routes naturally expose their parent lineage plus the
 * replacement node; a superseded target node is never injected into the
 * replacement route's lineage merely because {@code supersedesNodeId} points
 * at it.
 */
@Service
public class RouteLineageQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;

    public RouteLineageQueryService(ProjectService projectService,
                                    RouteService routeService,
                                    NodeService nodeService) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
    }

    public RouteLineageView getForRoute(UUID projectId, UUID routeId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.ROUTE_NOT_FOUND, "Route not found"));
        if (!route.projectId().equals(projectId)) {
            // A foreign route is indistinguishable from a missing one at the
            // API edge; both surface as 404 ROUTE_NOT_FOUND.
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.ROUTE_NOT_FOUND, "Route not found");
        }

        if (route.tipNodeId() == null) {
            return RouteLineageView.empty(project.id(), route.id(), route.rootNodeId(),
                    route.lifecycleStatus().code(), route.isActive(project.activeRouteId()));
        }

        List<RouteLineageNodeView> rootToTip = resolveLineage(project.id(), route);
        return new RouteLineageView(project.id(), route.id(), route.rootNodeId(), route.tipNodeId(),
                route.lifecycleStatus().code(), route.isActive(project.activeRouteId()), rootToTip);
    }

    private List<RouteLineageNodeView> resolveLineage(UUID projectId, Route route) {
        List<Node> rootToTip;
        try {
            rootToTip = ReadModelLineageWalker.walk(route.tipNodeId(), nodeService::getNode);
        } catch (ReadModelLineageWalker.LineageTraversalException ex) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION, ex.getMessage());
        }

        for (Node node : rootToTip) {
            if (!node.projectId().equals(projectId)) {
                // Fail closed: neither the foreign node nor any node beyond it
                // may be exposed in the response.
                throw RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                        "A node in the route lineage belongs to another project");
            }
        }

        List<RouteLineageNodeView> views = rootToTip.stream()
                .map(RouteLineageNodeView::from)
                .toList();

        if (route.rootNodeId() == null) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                    "Route has a tip node but no root node");
        }
        if (!route.rootNodeId().equals(views.get(0).id())) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                    "Route root node does not match the resolved lineage");
        }
        return List.copyOf(views);
    }
}
