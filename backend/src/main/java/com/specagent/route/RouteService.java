package com.specagent.route;

import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages explicit exploration routes, their lifecycle status, the active route
 * pointer, and deterministic route control operations (fork and regenerate).
 *
 * <p>Lifecycle status is {@code open | superseded | archived | deleted}. The
 * active route is tracked by {@code Project.activeRouteId}, never by a route
 * status. This service is deterministic and does not call any model.
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final ProjectRepository projectRepository;
    private final NodeRepository nodeRepository;
    private final NodeService nodeService;
    private final ContextBuilder contextBuilder;
    private final Json json;

    public RouteService(RouteRepository routeRepository,
                        ProjectRepository projectRepository,
                        NodeRepository nodeRepository,
                        NodeService nodeService,
                        ContextBuilder contextBuilder,
                        Json json) {
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.contextBuilder = contextBuilder;
        this.json = json;
    }

    public Route createRoute(UUID projectId, RouteLifecycleStatus status, String label) {
        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, null, null, status, label,
                null, null, null, null, now, now);
        routeRepository.save(route);
        return route;
    }

    public void updateTip(UUID routeId, UUID tipNodeId, UUID rootNodeId) {
        routeRepository.updateTipAndRoot(routeId, tipNodeId, rootNodeId, Instant.now());
    }

    private void markRouteSuperseded(UUID routeId) {
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.SUPERSEDED, Instant.now());
    }

    public Optional<Route> getRoute(UUID routeId) {
        return routeRepository.findById(routeId);
    }

    public List<Route> listRoutes(UUID projectId) {
        return routeRepository.findByProject(projectId);
    }

    /**
     * Sets the active route for a project. The route must exist, belong to the
     * project, and be {@code OPEN}. The active route is represented only by
     * {@code Project.activeRouteId}; the route lifecycle status is never changed
     * to {@code active}.
     */
    public void setActiveRoute(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN route can become active: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
    }

    /**
     * Archives an open route. If the archived route is the project's active
     * route, the active route is cleared. No nodes, answers, patches, or shared
     * ancestors are deleted, and no other route is implicitly activated.
     */
    public void archiveRoute(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.ARCHIVED, Instant.now());
        clearActiveRouteIfMatches(projectId, routeId);
    }

    /**
     * Soft-deletes a route by marking it {@code DELETED}. Historical data is
     * preserved. If the deleted route is the project's active route, the active
     * route is cleared.
     */
    public void softDeleteRoute(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.DELETED, Instant.now());
        clearActiveRouteIfMatches(projectId, routeId);
    }

    /**
     * Explicitly restores an archived, deleted, or superseded route back to
     * {@code OPEN} and makes it the project's active route.
     */
    public void restoreRoute(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.OPEN, Instant.now());
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
    }

    /**
     * Forks a new route view from a historical node. The fork route points at the
     * same immutable node lineage: root stays the source route's root, tip is the
     * source node, and {@code createdFromNodeId} records the fork origin. No
     * nodes, answers, patches, or sibling routes are copied, and the old route is
     * not modified. The new route becomes the project's active route.
     */
    public Route forkFromNode(UUID projectId, UUID sourceNodeId, String label) {
        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + sourceNodeId));
        if (!sourceNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + sourceNodeId + " does not belong to project " + projectId);
        }

        Route sourceRoute = findSourceRouteForNode(projectId, sourceNodeId);

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route forkRoute = new Route(routeId, projectId, sourceRoute.rootNodeId(), sourceNodeId,
                RouteLifecycleStatus.OPEN, label, sourceNodeId, null, null, null, now, now);
        routeRepository.save(forkRoute);
        projectRepository.updateActiveRoute(projectId, routeId, now);
        return forkRoute;
    }

    /**
     * Deterministically regenerates a historical node without calling a model.
     *
     * <p>The old route is marked {@code SUPERSEDED}; a replacement node and a new
     * open route are created; the new route becomes active. A regenerate context
     * snapshot is built from the parent lineage only, excluding the target node,
     * its answers, patches, child subtree, and sibling conclusions.
     */
    public RegenerateResult regenerateFromNode(UUID projectId,
                                                UUID targetNodeId,
                                                String userInstruction,
                                                String replacementQuestion,
                                                String replacementPurpose,
                                                List<NodeOption> replacementOptions) {
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Target node " + targetNodeId + " does not belong to project " + projectId);
        }

        // Phase 3.5: Root node regeneration is not supported yet
        if (targetNode.parentNodeId() == null) {
            throw new IllegalStateException("Root node regeneration is not supported yet");
        }

        // Find the source route that covers this node (must be OPEN)
        Route sourceRoute = findSourceRouteForNode(projectId, targetNodeId);
        UUID oldRouteId = sourceRoute.id();

        // Mark the old route as SUPERSEDED
        markRouteSuperseded(oldRouteId);

        // Create the replacement route first; its root inherits the old route's
        // root and its tip is assigned once the replacement node exists. The old
        // route's root and tip are never modified.
        UUID replacementRouteId = Ids.random();
        Instant now = Instant.now();
        Route replacementRoute = new Route(
                replacementRouteId,
                projectId,
                sourceRoute.rootNodeId(),
                null,
                RouteLifecycleStatus.OPEN,
                "Regenerated from " + targetNodeId,
                null,
                oldRouteId,
                targetNodeId,
                null,
                now,
                now
        );
        routeRepository.save(replacementRoute);

        // Create the replacement node as part of the replacement route, which
        // advances only that route's tip to the new node.
        Node replacementNode = nodeService.createReplacementNode(
                projectId, replacementRouteId, targetNode.parentNodeId(),
                targetNodeId, replacementQuestion, replacementPurpose,
                replacementOptions, true);

        // Build regenerate context snapshot
        ContextSnapshot contextSnapshot = contextBuilder.buildForRegenerate(
                projectId,
                oldRouteId,
                targetNodeId,
                replacementRouteId,
                replacementNode.id(),
                userInstruction
        );

        // Update project active route
        projectRepository.updateActiveRoute(projectId, replacementRouteId, now);

        // Re-read both routes to ensure we have the latest state
        Route updatedOldRoute = routeRepository.findById(oldRouteId)
                .orElseThrow(() -> new IllegalStateException("Old route not found after update"));
        Route updatedReplacementRoute = routeRepository.findById(replacementRouteId)
                .orElseThrow(() -> new IllegalStateException("Replacement route not found after save"));

        return new RegenerateResult(
                updatedOldRoute,
                updatedReplacementRoute,
                replacementNode,
                contextSnapshot);
    }

    /**
     * Finds the source route for a node. Only OPEN routes can be source routes.
     * First checks the project's active route; if it's OPEN and contains the node,
     * returns it. Otherwise, scans all OPEN routes in the project and returns the
     * one whose lineage contains the node. If multiple routes match, the one with
     * the latest creation time is preferred.
     *
     * <p>When checking lineage, this method also considers replacement relationships:
     * if a route's tip/root has been replaced by another node (via supersedesNodeId),
     * the original node is still considered part of the lineage for the purpose of
     * finding a source route.
     */
    private Route findSourceRouteForNode(UUID projectId, UUID nodeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // First check if the active route is OPEN and contains the node
        if (project.activeRouteId() != null) {
            Route activeRoute = routeRepository.findById(project.activeRouteId())
                    .orElse(null);
            if (activeRoute != null
                    && activeRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN
                    && lineageContainsWithReplacement(activeRoute, nodeId)) {
                return activeRoute;
            }
        }

        // Scan all OPEN routes in the project
        List<Route> openRoutes = routeRepository.findByProject(projectId).stream()
                .filter(r -> r.lifecycleStatus() == RouteLifecycleStatus.OPEN)
                .sorted(Comparator.comparing(Route::createdAt).reversed())
                .toList();

        for (Route route : openRoutes) {
            if (lineageContainsWithReplacement(route, nodeId)) {
                return route;
            }
        }

        throw new IllegalStateException(
                "No OPEN route found that contains node " + nodeId + " in project " + projectId);
    }

    /**
     * Checks if a route's lineage contains a given node, considering replacement
     * relationships. A node is considered part of the lineage if:
     * 1. It is the root or tip of the route
     * 2. It is the createdFromNodeId
     * 3. It is the supersedesNodeId of a node in the lineage (i.e., it has been
     *    replaced by a node in the lineage)
     */
    private boolean lineageContainsWithReplacement(Route route, UUID nodeId) {
        // First check simple lineage
        if (lineageContains(route, nodeId)) {
            return true;
        }

        // Check if this node has been replaced by a node in the lineage
        // Walk up from tip to root, checking if any node's supersedesNodeId matches nodeId
        UUID current = route.tipNodeId();
        Set<UUID> visited = new HashSet<>();
        int guard = 0;
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            Node currentNode = nodeRepository.findById(current).orElse(null);
            if (currentNode != null && nodeId.equals(currentNode.supersedesNodeId())) {
                return true;
            }
            current = currentNode != null ? currentNode.parentNodeId() : null;
            if (++guard > 10_000) {
                break;
            }
        }

        // Also check root if it's different from the tip lineage
        if (route.rootNodeId() != null && !route.rootNodeId().equals(route.tipNodeId())) {
            current = route.rootNodeId();
            visited.clear();
            guard = 0;
            while (current != null && !visited.contains(current)) {
                visited.add(current);
                Node currentNode = nodeRepository.findById(current).orElse(null);
                if (currentNode != null && nodeId.equals(currentNode.supersedesNodeId())) {
                    return true;
                }
                current = currentNode != null ? currentNode.parentNodeId() : null;
                if (++guard > 10_000) {
                    break;
                }
            }
        }

        return false;
    }

    /**
     * Checks if a route's lineage contains a given node. A route's lineage is
     * defined by its rootNodeId and tipNodeId. This is a simplified check.
     */
    private boolean lineageContains(Route route, UUID nodeId) {
        // Simple check: if the node is the root or tip, it's in the lineage
        if (route.rootNodeId() != null && route.rootNodeId().equals(nodeId)) {
            return true;
        }
        if (route.tipNodeId() != null && route.tipNodeId().equals(nodeId)) {
            return true;
        }

        // For more complex lineage checks, we would traverse the node graph
        // For now, check if the node is in the route's createdFromNodeId chain
        if (route.createdFromNodeId() != null && route.createdFromNodeId().equals(nodeId)) {
            return true;
        }

        return false;
    }

    private Route requireRouteInProject(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Route " + routeId + " does not belong to project " + projectId);
        }
        return route;
    }

    private void clearActiveRouteIfMatches(UUID projectId, UUID routeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() != null && project.activeRouteId().equals(routeId)) {
            projectRepository.updateActiveRoute(projectId, null, Instant.now());
        }
    }

    /**
     * @deprecated Use the specific lifecycle operations instead. This is only for
     * testing and should not be used in production code.
     */
    @Deprecated
    void changeLifecycleForTesting(UUID routeId, RouteLifecycleStatus status) {
        routeRepository.updateLifecycle(routeId, status, Instant.now());
    }
}
