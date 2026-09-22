package com.specagent.route;

import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
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
    private final ProjectActiveRoutePort projectPort;
    private final NodeRepository nodeRepository;
    private final NodeService nodeService;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RouteGraphSupportPort graphSupport;
    private final RouteMembershipProjectionPort routeMembershipProjection;

    public RouteService(RouteRepository routeRepository,
                        ProjectActiveRoutePort projectPort,
                         NodeRepository nodeRepository,
                         NodeService nodeService,
                         RouteHistoryResolver routeHistoryResolver,
                         RouteGraphSupportPort graphSupport,
                         RouteMembershipProjectionPort routeMembershipProjection) {
        this.routeRepository = routeRepository;
        this.projectPort = projectPort;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.graphSupport = graphSupport;
        this.routeMembershipProjection = routeMembershipProjection;
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

    private void refreshRouteAffectedSources(UUID projectId,
                                             UUID routeId,
                                             UUID lineageTipNodeId) {
        List<UUID> lineageRoots = lineageTipNodeId == null
                ? List.of()
                : routeHistoryResolver.resolveLineage(lineageTipNodeId);
        routeMembershipProjection.refreshRouteAffectedSources(projectId, routeId, lineageRoots);
    }

    /**
     * Bare lifecycle transition without the active-pointer side effects and
     * without an operation-log entry. This is the mutation core shared by the
     * user-facing lifecycle commands (which log) and the undo/redo
     * compensations (which must NOT log — the compensation acts ON an
     * existing log entry, it is not new user work). Callers own any
     * active-route pointer maintenance.
     */
    @Transactional
    public void transitionLifecycle(UUID projectId, UUID routeId, RouteLifecycleStatus target) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, target);
        routeRepository.updateLifecycle(routeId, target, Instant.now());
    }

    /** Active-route pointer restore used by undo/redo compensations. */
    @Transactional
    public void setActiveRoutePointer(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
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
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN route can become active: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
    }

    /**
     * Archives an open route. If the archived route is the project's active
     * route, the active route is cleared. No nodes, answers, patches, or shared
     * ancestors are deleted, and no other route is implicitly activated.
     */
    @Transactional
    public void archiveRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.ARCHIVED);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.ARCHIVED);
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    /**
     * Soft-deletes a route by marking it {@code DELETED}. Historical data is
     * preserved. If the deleted route is the project's active route, the active
     * route is cleared.
     */
    @Transactional
    public void softDeleteRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.DELETED);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.DELETED);
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    /**
     * Explicitly restores an archived, deleted, or superseded route back to
     * {@code OPEN} and makes it the project's active route.
     */
    @Transactional
    public void restoreRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.OPEN);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.OPEN);
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    private UUID currentActiveRouteId(UUID projectId) {
        return projectPort.findActiveRouteId(projectId).orElse(null);
    }

    private void appendLifecycleOperation(UUID projectId, UUID routeId,
                                          RouteLifecycleStatus fromStatus,
                                          UUID previousActiveRouteId) {
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_LIFECYCLE, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "fromStatus", fromStatus.code(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "toStatus", routeRepository.findById(routeId)
                               .map(r -> r.lifecycleStatus().code())
                               .orElse(RouteLifecycleStatus.DELETED.code())));
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
        // Serialize with archive/restore/activate and graph mutations: the fork
        // reads the source route lifecycle and creates a new Active route, so
        // it must hold the project-row lock before any state read (order:
        // project → node/route → mutation).
        projectPort.lockProject(projectId);
        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + sourceNodeId));
        if (!sourceNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + sourceNodeId + " does not belong to project " + projectId);
        }

        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphSupport.validateRouteProvenance(sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        // 分支点必须是本路线的成员:谱系上的节点,或挂在谱系节点下的派生
        // 知识/资源(它们不出现在 tip 父链上,但属于本路线)。
        if (!routeHistoryResolver.belongsToRoute(sourceRoute, sourceNodeId)) {
            throw new IllegalArgumentException(
                    "Node is not on the explicit source route: " + sourceNodeId);
        }
        // 只有可回答的问题节点才要求"已回答才能分叉":从一个未回答的问题处分叉
        // 会把悬而未决的问题埋进分支。知识/资源节点没有可回答状态,天然可以
        // 作为分支点(继续生成问题的入口)。
        if (sourceNode.kind() == NodeKind.INTERACTION
                && routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
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
        refreshRouteAffectedSources(projectId, routeId, sourceNodeId);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_FORK, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "branchAtNodeId", sourceNodeId.toString(),
                       "tipNodeId", sourceNodeId.toString()));
        return forkRoute;
    }

    /**
     * Starts a NEW standalone route from a floating (route-less) node — the
     * "想法继续生成问题" entry: the idea becomes the new route's root and tip,
     * the next question draft anchors at it, and the new route becomes the
     * project's active route. The node keeps its id, kind and content and is
     * never copied; the source route (if the node visually hangs under one)
     * is untouched. The node must not already belong to any route lineage.
     */
    @Transactional
    public Route startRouteFromNode(UUID projectId, UUID nodeId, String label) {
        // Serialize like fork: project lock before any read/mutation.
        projectPort.lockProject(projectId);
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + nodeId + " does not belong to project " + projectId);
        }
        if (node.kind() == NodeKind.INTERACTION) {
            throw new IllegalArgumentException(
                    "Only a floating knowledge/resource node can start a route: " + nodeId);
        }
        for (Route candidate : routeRepository.findByProject(projectId)) {
            if (candidate.lifecycleStatus() == RouteLifecycleStatus.DELETED) {
                continue;
            }
            if (routeHistoryResolver.belongsToRoute(candidate, nodeId)) {
                throw new IllegalStateException(
                        "Node already belongs to a route lineage: " + nodeId);
            }
        }

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, nodeId, nodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, null, label),
                null, null, null, null, now, now);
        routeRepository.save(route);
        refreshRouteAffectedSources(projectId, routeId, nodeId);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_START, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "rootNodeId", nodeId.toString(),
                       "tipNodeId", nodeId.toString()));
        return route;
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
        projectPort.lockProject(projectId);
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);
        if (routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                .noneMatch(answer -> answer.nodeId().equals(targetNodeId))) {
            throw new IllegalStateException("Re-answer target has no finalized answer: " + targetNodeId);
        }
        graphSupport.validateRouteProvenance(sourceRouteId);

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
        Node clonedNode = nodeService.createReanswerNode(
                projectId, routeId, targetNode.parentNodeId(),
                targetNode.question(), targetNode.purpose(), targetNode.options(),
                targetNode.allowFreeAnswer(), targetNode.allowMultiSelect());
        refreshRouteAffectedSources(projectId, routeId, targetNode.parentNodeId());
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_REANSWER, List.of(routeId, clonedNode.id()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "clonedNodeId", clonedNode.id().toString(),
                       "replacedNodeId", targetNodeId.toString(),
                       "tipNodeId", clonedNode.id().toString()));
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Re-answer route missing after creation: " + routeId));
    }

    /**
     * Commits an already accepted replacement proposal. The method is the only
     * place that creates canonical replacement history: proposal parsing,
     * reflection, and validation happen before entering this transaction.
     *
     * <p>{@code expectedSourceRouteTip} freezes the source tip the decision was
     * made against. The caller captures it BEFORE this transaction; inside the
     * transaction, after the project lock is held, the source route is re-read
     * and the expected tip is re-verified, closing the check-then-act window
     * against a concurrent continuation that advanced the source tip.
     */
    @Transactional
    public RegenerateResult commitReplacementFromNode(UUID projectId,
                                                      UUID sourceRouteId,
                                                      UUID targetNodeId,
                                                      UUID expectedSourceRouteTip,
                                                      String label,
                                                      String question,
                                                      String purpose,
                                                      List<NodeOption> options,
                                                      boolean allowFreeAnswer) {
        return commitReplacementFromNode(projectId, sourceRouteId, targetNodeId,
                expectedSourceRouteTip, label, question, purpose, options, allowFreeAnswer, false);
    }

    /** Multi-select-aware replacement commit (copies the model's flag). */
    @Transactional
    public RegenerateResult commitReplacementFromNode(UUID projectId,
                                                      UUID sourceRouteId,
                                                      UUID targetNodeId,
                                                      UUID expectedSourceRouteTip,
                                                      String label,
                                                      String question,
                                                      String purpose,
                                                      List<NodeOption> options,
                                                      boolean allowFreeAnswer,
                                                      boolean allowMultiSelect) {
        // Serialize with archive/restore/activate and graph mutations: the
        // replacement supersedes the source route and changes Active, so the
        // project-row lock is taken before any state read. The stale-tip check
        // happens under this lock, so a concurrent continuation can never
        // advance the source tip between the decision and the commit.
        projectPort.lockProject(projectId);
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        if (targetNode.parentNodeId() == null) {
            throw new IllegalStateException("Root node replacement is not supported");
        }
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphSupport.validateRouteProvenance(sourceRouteId);
        // Re-verify under the project lock that the source has not moved since
        // the frozen decision: the tip must still be exactly the expected one,
        // and the target must still sit on that exact source lineage.
        if (!java.util.Objects.equals(sourceRoute.tipNodeId(), expectedSourceRouteTip)) {
            throw new IllegalStateException(
                    "Source route tip moved after the replacement snapshot: expected "
                            + expectedSourceRouteTip + ", current " + sourceRoute.tipNodeId());
        }
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);
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
                question.trim(), purpose, options == null ? List.of() : options, allowFreeAnswer,
                allowMultiSelect);
        refreshRouteAffectedSources(projectId, replacementRouteId, targetNode.parentNodeId());

        boolean sourceWasOpen = sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN;
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        if (sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN) {
            markRouteSuperseded(sourceRouteId);
        }
        projectPort.updateActiveRoute(projectId, replacementRouteId, now);

        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_REGENERATE,
                List.of(replacementRouteId, replacementNode.id()),
                Map.<String, Object>of("routeId", replacementRouteId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "sourceWasOpen", sourceWasOpen,
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", replacementRouteId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "replacementNodeId", replacementNode.id().toString(),
                       "replacedNodeId", targetNodeId.toString(),
                       "tipNodeId", replacementNode.id().toString()));

        Route updatedSource = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalStateException("Source route not found after replacement"));
        Route updatedReplacement = routeRepository.findById(replacementRouteId)
                .orElseThrow(() -> new IllegalStateException("Replacement route not found after commit"));
        return new RegenerateResult(updatedSource, updatedReplacement, replacementNode);
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
        String prefix = branchType == null ? "想法路线" : switch (branchType) {
            case FORK -> "分支路线";
            case REANSWER -> "重新回答路线";
            case REGENERATE -> "换题路线";
            case CONTINUATION -> "探索分支";
        };
        return prefix + " " + (count + 1);
    }

    private void clearActiveRouteIfMatches(UUID projectId, UUID routeId) {
        // Port contract: a missing project throws inside the port; an existing
        // project without an active route yields null here (original semantics).
        UUID activeRouteId = projectPort.findActiveRouteId(projectId).orElse(null);
        if (activeRouteId != null && activeRouteId.equals(routeId)) {
            projectPort.updateActiveRoute(projectId, null, Instant.now());
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
        // Port contract: a missing project throws inside the port; an existing
        // project without an active route yields null here (original semantics).
        UUID activeRouteId = projectPort.findActiveRouteId(projectId).orElse(null);
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
