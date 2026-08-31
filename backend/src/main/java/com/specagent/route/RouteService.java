package com.specagent.route;

import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final RouteHistoryResolver routeHistoryResolver;
    private final com.specagent.graph.GraphOperationRepository graphOperationRepository;
    private final com.specagent.graph.GraphInvariantValidator graphInvariantValidator;

    public RouteService(RouteRepository routeRepository,
                        ProjectRepository projectRepository,
                        NodeRepository nodeRepository,
                        NodeService nodeService,
                        RouteHistoryResolver routeHistoryResolver,
                        com.specagent.graph.GraphOperationRepository graphOperationRepository,
                        com.specagent.graph.GraphInvariantValidator graphInvariantValidator) {
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.graphOperationRepository = graphOperationRepository;
        this.graphInvariantValidator = graphInvariantValidator;
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

    /** Valid explicit source for branch/exploration mutations. */
    public Route requireExplorationSource(UUID projectId, UUID sourceRouteId) {
        Route route = requireRouteInProject(projectId, sourceRouteId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN
                && route.lifecycleStatus() != RouteLifecycleStatus.SUPERSEDED) {
            throw new IllegalStateException("Only an OPEN or SUPERSEDED route can be an exploration source");
        }
        return route;
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
    @Transactional
    public void setActiveRoute(UUID projectId, UUID routeId) {
        projectRepository.lockById(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN route can become active: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
    }

    /**
     * Archives an open route. If the archived route is the project's active
     * route, the active route is cleared. No nodes, answers, patches, or shared
     * ancestors are deleted, and no other route is implicitly activated.
     */
    @Transactional
    public void archiveRoute(UUID projectId, UUID routeId) {
        projectRepository.lockById(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.ARCHIVED);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.ARCHIVED, Instant.now());
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
    }

    /**
     * Soft-deletes a route by marking it {@code DELETED}. Historical data is
     * preserved. If the deleted route is the project's active route, the active
     * route is cleared.
     */
    @Transactional
    public void softDeleteRoute(UUID projectId, UUID routeId) {
        projectRepository.lockById(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.DELETED);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.DELETED, Instant.now());
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
    }

    /**
     * Explicitly restores an archived, deleted, or superseded route back to
     * {@code OPEN} and makes it the project's active route.
     */
    @Transactional
    public void restoreRoute(UUID projectId, UUID routeId) {
        projectRepository.lockById(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.OPEN);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.OPEN, Instant.now());
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
    }

    /**
     * Forks a new route view from a historical node. The fork route points at the
     * same immutable node lineage: root stays the source route's root, tip is the
     * source node, and {@code createdFromNodeId} records the fork origin. No
     * nodes, answers, patches, or sibling routes are copied, and the old route is
     * not modified. The new route becomes the project's active route.
     */
    /**
     * Explicit-source Fork. The accepted answer at the branch point is frozen
     * as an immutable reference in the new route prefix.
     */
    @Transactional
    public Route forkFromNode(UUID projectId, UUID sourceRouteId, UUID sourceNodeId, String label) {
        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + sourceNodeId));
        if (!sourceNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + sourceNodeId + " does not belong to project " + projectId);
        }

        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphInvariantValidator.validateRouteProvenance(sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, sourceNodeId);
        if (routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                .noneMatch(answer -> answer.nodeId().equals(sourceNodeId))) {
            throw new IllegalStateException("Fork branch point has no finalized answer: " + sourceNodeId);
        }

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route forkRoute = new Route(routeId, projectId, sourceRoute.rootNodeId(), sourceNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.FORK, label), sourceNodeId, null, null, null,
                RouteBranchType.FORK, sourceRouteId, sourceNodeId, now, now);
        routeRepository.save(forkRoute);
        routeHistoryResolver.snapshotInheritedPrefix(routeId, sourceRouteId, sourceNodeId, true);
        projectRepository.updateActiveRoute(projectId, routeId, now);
        return forkRoute;
    }

    /**
     * Creates a Re-answer route whose tip is a NEW Question Node. The old
     * Question is never reused: its immutable semantics (question, purpose,
     * options, allowFreeAnswer) are copied onto a fresh canonical id sharing
     * the old parent, the inherited prefix freezes the old Question's
     * ancestors only (the old Answer stays on the source route), and the
     * source route and its Answers are left untouched.
     */
    @Transactional
    public Route reanswerFromNode(UUID projectId,
                                  UUID sourceRouteId,
                                  UUID targetNodeId,
                                  String label) {
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);
        if (routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                .noneMatch(answer -> answer.nodeId().equals(targetNodeId))) {
            throw new IllegalStateException("Re-answer target has no finalized answer: " + targetNodeId);
        }
        graphInvariantValidator.validateRouteProvenance(sourceRouteId);

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        // The new route's history starts at the old target's position: root
        // stays the source root, tip is temporarily the old target so the
        // frozen inherited prefix is anchored, then the cloned Question
        // advances the tip onto its own canonical identity.
        Route route = new Route(routeId, projectId,
                targetNode.parentNodeId() == null ? null : sourceRoute.rootNodeId(),
                targetNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.REANSWER, label),
                targetNodeId, null, null, null,
                RouteBranchType.REANSWER, sourceRouteId, targetNodeId, now, now);
        routeRepository.save(route);
        // Inherited prefix excludes the old target's answer: the re-answered
        // Question starts waiting again.
        routeHistoryResolver.snapshotInheritedPrefix(routeId, sourceRouteId, targetNodeId, false);
        nodeService.createReanswerNode(
                projectId, routeId, targetNode.parentNodeId(),
                targetNode.question(), targetNode.purpose(), targetNode.options(),
                targetNode.allowFreeAnswer());
        projectRepository.updateActiveRoute(projectId, routeId, now);
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Re-answer route missing after creation: " + routeId));
    }

    /**
     * Commits an already accepted replacement proposal. The method is the only
     * place that creates canonical replacement history: proposal parsing,
     * reflection, and validation happen before entering this transaction.
     */
    @Transactional
    public RegenerateResult commitReplacementFromNode(UUID projectId,
                                                      UUID sourceRouteId,
                                                      UUID targetNodeId,
                                                      String label,
                                                      String question,
                                                      String purpose,
                                                      List<NodeOption> options,
                                                      boolean allowFreeAnswer) {
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        if (targetNode.parentNodeId() == null) {
            throw new IllegalStateException("Root node replacement is not supported");
        }
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphInvariantValidator.validateRouteProvenance(sourceRouteId);
        requireLineageContains(routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId()), targetNodeId);
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Replacement question must not be blank");
        }
        if (normalize(question).equals(normalize(targetNode.question()))) {
            throw new IllegalArgumentException("Replacement question must differ from the rejected question");
        }

        UUID replacementRouteId = Ids.random();
        Instant now = Instant.now();
        Route replacementRoute = new Route(
                replacementRouteId,
                projectId,
                sourceRoute.rootNodeId(),
                null,
                RouteLifecycleStatus.OPEN,
                effectiveLabel(projectId, RouteBranchType.REGENERATE, label),
                targetNode.parentNodeId(),
                sourceRouteId,
                targetNodeId,
                null,
                RouteBranchType.REGENERATE,
                sourceRouteId,
                targetNodeId,
                now,
                now);
        routeRepository.save(replacementRoute);

        routeHistoryResolver.snapshotInheritedPrefix(
                replacementRouteId, sourceRouteId, targetNode.parentNodeId(), true);
        Node replacementNode = nodeService.createReplacementNode(
                projectId, replacementRouteId, targetNode.parentNodeId(), targetNodeId,
                question.trim(), purpose, options == null ? List.of() : options, allowFreeAnswer);

        if (sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN) {
            markRouteSuperseded(sourceRouteId);
        }
        projectRepository.updateActiveRoute(projectId, replacementRouteId, now);

        Route updatedSource = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalStateException("Source route not found after replacement"));
        Route updatedReplacement = routeRepository.findById(replacementRouteId)
                .orElseThrow(() -> new IllegalStateException("Replacement route not found after commit"));
        return new RegenerateResult(updatedSource, updatedReplacement, replacementNode, null);
    }

    /**
     * Checks if a node lies on the route's lineage: the chain from
     * {@code tipNodeId} up through {@code parentNodeId} pointers to the root.
     * Replacement relationships are deliberately ignored here — a replacement
     * node never enters the normal lineage of the route it supersedes.
     */
    private boolean lineageContains(Route route, UUID nodeId) {
        if (nodeId == null || route.tipNodeId() == null) {
            return false;
        }

        UUID current = route.tipNodeId();
        Set<UUID> visited = new HashSet<>();
        int guard = 0;

        while (current != null && !visited.contains(current)) {
            if (current.equals(nodeId)) {
                return true;
            }

            visited.add(current);
            Node currentNode = nodeRepository.findById(current).orElse(null);
            current = currentNode != null ? currentNode.parentNodeId() : null;

            if (++guard > 10_000) {
                throw new IllegalStateException("Node lineage exceeds maximum depth");
            }
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

    private Route requireOpenRouteInProject(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException("Only an OPEN route can be a branch source: " + routeId);
        }
        return route;
    }

    private Node requireNodeInProject(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Node " + nodeId + " does not belong to project " + projectId);
        }
        return node;
    }

    private void requireLineageContains(List<UUID> lineage, UUID nodeId) {
        if (!lineage.contains(nodeId)) {
            throw new IllegalArgumentException("Node is not on the explicit source route: " + nodeId);
        }
    }

    private void requireTransition(Route route, RouteLifecycleStatus target) {
        boolean allowed = switch (route.lifecycleStatus()) {
            case OPEN -> target == RouteLifecycleStatus.ARCHIVED || target == RouteLifecycleStatus.DELETED;
            case SUPERSEDED -> target == RouteLifecycleStatus.OPEN
                    || target == RouteLifecycleStatus.ARCHIVED || target == RouteLifecycleStatus.DELETED;
            case ARCHIVED -> target == RouteLifecycleStatus.OPEN || target == RouteLifecycleStatus.DELETED;
            case DELETED -> target == RouteLifecycleStatus.OPEN;
        };
        if (!allowed) {
            throw new IllegalStateException("Illegal route lifecycle transition");
        }
    }

    private String effectiveLabel(UUID projectId, RouteBranchType branchType, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        long count = routeRepository.findByProject(projectId).stream()
                .filter(route -> route.branchType() == branchType)
                .count();
        String prefix = switch (branchType) {
            case FORK -> "分支路线";
            case REANSWER -> "重新回答路线";
            case REGENERATE -> "换题路线";
            case CONTINUATION -> "探索分支";
        };
        return prefix + " " + (count + 1);
    }

    private void clearActiveRouteIfMatches(UUID projectId, UUID routeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() != null && project.activeRouteId().equals(routeId)) {
            projectRepository.updateActiveRoute(projectId, null, Instant.now());
        }
    }

    /**
     * Fail-closed enforcement of the active-route pointer invariant, checked
     * after every lifecycle / active-route change while the project row is still
     * locked: {@code activeRouteId} is either null, or points to a route that
     * exists, belongs to this project, and has lifecycle status {@code OPEN}.
     *
     * <p>This is a safety net on top of the explicit maintenance above — it never
     * auto-selects another route when the active pointer would otherwise dangle.
     * A violation indicates either a regression in this service or pre-existing
     * corruption, and is surfaced as a stable {@code IllegalStateException}.
     */
    private void assertActiveRouteInvariant(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        UUID activeRouteId = project.activeRouteId();
        if (activeRouteId == null) {
            return;
        }
        Route active = routeRepository.findById(activeRouteId)
                .orElseThrow(() -> new IllegalStateException(
                        "ACTIVE_ROUTE_DANGLING: project " + projectId
                                + " activeRouteId " + activeRouteId + " references a missing route"));
        if (!active.projectId().equals(projectId)) {
            throw new IllegalStateException(
                    "ACTIVE_ROUTE_CROSS_PROJECT: project " + projectId
                            + " activeRouteId " + activeRouteId + " belongs to another project");
        }
        if (active.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "ACTIVE_ROUTE_NOT_OPEN: project " + projectId
                            + " activeRouteId " + activeRouteId + " is not OPEN: "
                            + active.lifecycleStatus().code());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
