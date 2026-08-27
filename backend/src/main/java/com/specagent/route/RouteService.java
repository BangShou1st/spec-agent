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

    public RouteService(RouteRepository routeRepository,
                        ProjectRepository projectRepository,
                        NodeRepository nodeRepository,
                        NodeService nodeService,
                        RouteHistoryResolver routeHistoryResolver,
                        com.specagent.graph.GraphOperationRepository graphOperationRepository) {
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.graphOperationRepository = graphOperationRepository;
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
        requireTransition(route, RouteLifecycleStatus.ARCHIVED);
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
        requireTransition(route, RouteLifecycleStatus.DELETED);
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.DELETED, Instant.now());
        clearActiveRouteIfMatches(projectId, routeId);
    }

    /**
     * Explicitly restores an archived, deleted, or superseded route back to
     * {@code OPEN} and makes it the project's active route.
     */
    public void restoreRoute(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.OPEN);
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
    /**
     * Explicit-source Fork. The accepted answer at the branch point is frozen
     * as an immutable reference in the new route prefix.
     */
    public Route forkFromNode(UUID projectId, UUID sourceRouteId, UUID sourceNodeId, String label) {
        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + sourceNodeId));
        if (!sourceNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + sourceNodeId + " does not belong to project " + projectId);
        }

        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
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

    /** Creates a Re-answer route with the target Question waiting again. */
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

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, sourceRoute.rootNodeId(), targetNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.REANSWER, label), targetNodeId, null, null, null,
                RouteBranchType.REANSWER, sourceRouteId, targetNodeId, now, now);
        routeRepository.save(route);
        routeHistoryResolver.snapshotInheritedPrefix(routeId, sourceRouteId, targetNodeId, false);
        projectRepository.updateActiveRoute(projectId, routeId, now);
        return route;
    }

    /**
     * Reactivates a historical unanswered Question on an explicit source route
     * without rewriting the source route's lineage and without copying or
     * retracting the canonical Question.
     *
     * <p>Decision table:
     * <ul>
     *   <li>Source route OPEN, source tip == target, target has no effective
     *       answer on the source route → activate the existing source route
     *       in place, no new route is created, no GraphOperation is appended.</li>
     *   <li>Source route OPEN, target is on the lineage but not the source
     *       tip, target has no effective answer on the source route → create
     *       a new RESUME_QUESTION branch route with inherited prefix that
     *       excludes the target itself.</li>
     *   <li>Otherwise → fail closed (IllegalStateException).</li>
     * </ul>
     */
    @Transactional
    public ResumeQuestionResult resumeAnsweringFromNode(UUID projectId,
                                                       UUID sourceRouteId,
                                                       UUID targetNodeId,
                                                       String label) {
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        if (targetNode.kind() != com.specagent.node.NodeKind.INTERACTION
                || !"QUESTION".equals(targetNode.subtype())) {
            throw new IllegalArgumentException(
                    "Resume target must be an INTERACTION/QUESTION node: " + targetNodeId);
        }
        if (targetNode.isRetracted()) {
            throw new IllegalStateException("Target Question has been retracted: " + targetNodeId);
        }
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);

        // Effective answer presence check: route-local + inherited on the source route.
        boolean alreadyAnsweredOnSource = routeHistoryResolver
                .resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                .anyMatch(answer -> answer.nodeId().equals(targetNodeId));
        if (alreadyAnsweredOnSource) {
            throw new IllegalStateException(
                    "Target Question already has a finalized effective answer on the source route: "
                            + targetNodeId);
        }

        // Capture previous Active BEFORE flipping, so Undo can restore it.
        UUID previousActive = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("Project missing: " + projectId))
                .activeRouteId();

        boolean sourceTipIsTarget = targetNodeId.equals(sourceRoute.tipNodeId());
        if (sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN && sourceTipIsTarget) {
            if (!sourceRouteId.equals(previousActive)) {
                projectRepository.updateActiveRoute(projectId, sourceRouteId, Instant.now());
            }
            return new ResumeQuestionResult(sourceRoute, false, previousActive);
        }

        // Create a new RESUME_QUESTION branch route. The tip is the existing
        // canonical Question, the inherited prefix freezes effective answers
        // strictly before the target, and the new route becomes Active.
        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route resumeRoute = new Route(
                routeId, projectId, sourceRoute.rootNodeId(), targetNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.RESUME_QUESTION, label),
                null, null, null, null,
                RouteBranchType.RESUME_QUESTION, sourceRouteId, targetNodeId, now, now);
        routeRepository.save(resumeRoute);
        projectRepository.updateActiveRoute(projectId, routeId, now);

        // Atomic append of the typed operation in the same transaction
        // boundary as the new route, the inherited prefix, and the Active
        // switch. Undo/Redo will treat this as a route-only operation and
        // never touch the canonical Question.
        // The frozen inherited prefix is also recorded in afterRefs so the
        // compensation can verify provenance against the actual refs row by
        // row (the list may be empty when the target Question is the very
        // first node being asked on the source route — that is legal).
        List<com.specagent.route.RouteInheritedAnswer> inheritedRefs = routeHistoryResolver
                .snapshotInheritedPrefix(routeId, sourceRouteId, targetNodeId, false);
        Map<String, Object> beforeRefs = previousActive == null
                ? Map.of()
                : Map.of("previousActiveRouteId", previousActive.toString());
        List<Map<String, Object>> serializedRefs = inheritedRefs.stream()
                .map(ref -> Map.<String, Object>of(
                        "nodeId", ref.nodeId().toString(),
                        "answerId", ref.answerId().toString(),
                        "ownerRouteId", ref.ownerRouteId().toString(),
                        "ordinal", ref.ordinal()))
                .toList();
        Map<String, Object> afterRefs = new java.util.LinkedHashMap<>();
        afterRefs.put("routeId", routeId.toString());
        afterRefs.put("sourceRouteId", sourceRouteId.toString());
        afterRefs.put("targetNodeId", targetNodeId.toString());
        afterRefs.put("branchType", RouteBranchType.RESUME_QUESTION.code());
        afterRefs.put("expectedInheritedRefs", serializedRefs);
        graphOperationRepository.append(projectId,
                com.specagent.graph.GraphOperation.Actor.USER,
                com.specagent.graph.GraphOperation.Type.RESUME_QUESTION_FROM_HISTORY,
                List.of(targetNodeId),
                beforeRefs,
                afterRefs);
        return new ResumeQuestionResult(resumeRoute, true, previousActive);
    }

    /**
     * Lightweight DTO for the resume command: the resulting route plus a
     * boolean telling the caller whether a new branch route was actually
     * created (true) or whether the existing source route was merely
     * reactivated (false).
     */
    public record ResumeQuestionResult(Route route, boolean createdNewRoute, UUID previousActiveRouteId) { }

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
            case RESUME_QUESTION -> "恢复回答路线";
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
