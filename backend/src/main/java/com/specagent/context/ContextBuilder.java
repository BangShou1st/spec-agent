package com.specagent.context;

import com.specagent.common.Hashes;
import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Objects;

/**
 * Builds a deterministic, lineage-based context snapshot for one agent run.
 *
 * <p>Context is not global chat history. It is the active route's tip replayed
 * through its parent lineage to the root, plus answers and patches on that
 * lineage. Sibling routes and superseded/archived/deleted routes are excluded by
 * default and recorded in {@code excludedRouteIds}.
 *
 * <p>This builder is deterministic and never calls a model or model gateway.
 */
@Service
public class ContextBuilder {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final NodeRelationRepository nodeRelationRepository;
    private final Json json;

    public ContextBuilder(ProjectRepository projectRepository,
                         RouteRepository routeRepository,
                         NodeRepository nodeRepository,
                         AnswerPatchRepository answerPatchRepository,
                         ContextSnapshotRepository contextSnapshotRepository,
                         Json json,
                         RouteHistoryResolver routeHistoryResolver,
                         NodeRelationRepository nodeRelationRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.json = json;
        this.routeHistoryResolver = routeHistoryResolver;
        this.nodeRelationRepository = nodeRelationRepository;
    }

    /**
     * Relation types admitted into the bounded 1-hop semantic context. Direction
     * is preserved exactly as stored; symmetric types are already canonicalized
     * at write time by {@code GraphInvariantValidator.endpointsCanonicalized}.
     */
    private static final java.util.Set<NodeRelationType> SEMANTIC_RELATION_TYPES =
            java.util.Set.of(NodeRelationType.RELATED_TO, NodeRelationType.DEPENDS_ON,
                    NodeRelationType.DERIVED_FROM, NodeRelationType.CONFLICTS_WITH,
                    NodeRelationType.SUPPORTS);

    public ContextSnapshot buildFromActiveRoute(UUID projectId, UUID agentRunId, ContextOperationType operationType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        UUID activeRouteId = project.activeRouteId();
        Route activeRoute = routeRepository.findById(activeRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + activeRouteId));

        if (activeRoute.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Active route is not OPEN: " + activeRouteId
                            + " is " + activeRoute.lifecycleStatus().code());
        }

        // Build lineage from tip to root along parent pointers. A route's
        // context is exactly its root-to-tip lineage; replacement nodes belong
        // to the replacement route's lineage and never enter this chain.
        List<UUID> lineage = resolveLineage(activeRoute.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(activeRouteId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(activeRouteId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of());
        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, activeRouteId,
                activeRoute.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                json.write(specialInputsMap), contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * Builds a route-bound context snapshot for one explicit run target. The
     * caller supplies the exact {@code routeId} and {@code inputNodeId} frozen
     * onto the run at enqueue time; this builder never reads the project's
     * active route pointer to choose a target, so a run that was queued
     * against route A keeps building context for route A even if the active
     * pointer moved to route B in the meantime.
     *
     * <p>The resulting snapshot always satisfies
     * {@code snapshot.routeId == routeId} and
     * {@code snapshot.tipNodeId == route.tipNodeId}; callers that require the
     * enqueue-time tip identity additionally verify
     * {@code inputNodeId == route.tipNodeId} (enforced here fail-closed).
     */
    public ContextSnapshot buildForRoute(UUID projectId,
                                         UUID routeId,
                                         UUID inputNodeId,
                                         UUID agentRunId,
                                         ContextOperationType operationType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Route " + routeId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Run target route is not OPEN: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        if (!Objects.equals(inputNodeId, route.tipNodeId())) {
            throw new IllegalStateException(
                    "Run input node is no longer the route tip: " + inputNodeId
                            + " (current tip: " + route.tipNodeId() + ")");
        }

        // Build lineage from tip to root along parent pointers. A route's
        // context is exactly its root-to-tip lineage; replacement nodes belong
        // to the replacement route's lineage and never enter this chain.
        List<UUID> lineage = resolveLineage(route.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(routeId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(routeId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of());
        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, routeId,
                route.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                json.write(specialInputsMap), contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    public ContextSnapshot buildForRegenerate(UUID projectId,
                                              UUID oldRouteId,
                                              UUID targetNodeId,
                                              UUID replacementRouteId,
                                              UUID replacementNodeId,
                                              String userInstruction) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));

        // Regenerate context carries only the shared parent lineage of the
        // target node: the target node itself, its answers, patches, and child
        // subtree are deliberately absent.
        List<UUID> parentLineage = resolveLineage(targetNode.parentNodeId());

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(oldRouteId, parentLineage)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(replacementRouteId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "oldQuestion", targetNode.question() == null ? "" : targetNode.question(),
                "oldPurpose", targetNode.purpose() == null ? "" : targetNode.purpose(),
                "userInstruction", userInstruction == null ? "" : userInstruction));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, replacementRouteId,
                replacementNodeId, ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), List.of(),
                specialInputs, contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * Freezes the pre-proposal context for model-powered question replacement.
     * The snapshot is anchored to the explicit source route and the rejected
     * node's parent tip. No accepted replacement route/node identity exists at
     * this point.
     */
    public ContextSnapshot buildForReplacement(UUID projectId,
                                               UUID sourceRouteId,
                                               UUID targetNodeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Source route not found: " + sourceRouteId));
        if (!sourceRoute.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Source route does not belong to project");
        }
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Target node does not belong to project");
        }

        List<UUID> sourceLineage = resolveLineage(sourceRoute.tipNodeId());
        if (!sourceLineage.contains(targetNodeId)) {
            throw new IllegalArgumentException("Target node is not on the explicit source route");
        }
        List<UUID> parentLineage = resolveLineage(targetNode.parentNodeId());
        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(sourceRouteId, parentLineage)
                .stream().map(answer -> answer.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(patch -> patch.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(Route::id)
                .filter(id -> !id.equals(sourceRouteId))
                .toList();
        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "oldQuestion", targetNode.question() == null ? "" : targetNode.question(),
                "oldPurpose", targetNode.purpose() == null ? "" : targetNode.purpose()));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(
                Ids.random(), projectId, sourceRouteId, targetNode.parentNodeId(),
                ContextOperationType.REGENERATE, parentLineage, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                specialInputs, contextHash, Instant.now());
        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * Builds a read context anchored at an arbitrary node for a contextual
     * AI query ("ask AI about this node").
     *
     * <p>The lineage is the anchor's own root-to-anchor chain along parent
     * pointers; the read context is the explicit {@code routeId} the caller
     * chose (never an active/first/latest fallback). The user's question is
     * frozen into {@code specialInputs} so it is part of the context hash.
     */
    public ContextSnapshot buildForNodeQuery(UUID projectId,
                                             UUID routeId,
                                             UUID anchorNodeId,
                                             String userQuestion) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        // The route is OPTIONAL reading context for a node query. A floating
        // node belongs to no route (routeIds=[]); its query context is the
        // anchor node itself. routeId must never be a hard eligibility gate.
        Route route = null;
        if (routeId != null) {
            route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
            if (!route.projectId().equals(projectId)) {
                throw new IllegalArgumentException("Route does not belong to project: " + routeId);
            }
        }
        Node anchor = nodeRepository.findById(anchorNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Anchor node not found: " + anchorNodeId));
        if (!anchor.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Anchor node does not belong to project: " + anchorNodeId);
        }

        // B4.3 — anchor eligibility, fail closed:
        // - a retracted anchor can never be the subject of a query;
        // - a route-bound query requires the anchor to sit on that route's
        //   canonical lineage (shared/cross-route anchors are rejected);
        // - a routeless (routeId == null) query requires the anchor to be a
        //   genuine floating node: if it actually belongs to any route we must
        //   not silently treat it as routeless.
        if (anchor.isRetracted()) {
            throw new IllegalArgumentException("Anchor node is retracted: " + anchorNodeId);
        }
        if (routeId != null) {
            if (!resolveLineage(route.tipNodeId()).contains(anchorNodeId)) {
                throw new IllegalArgumentException(
                        "Anchor node is not on the explicit route lineage: " + anchorNodeId);
            }
        } else if (isAnchorMemberOfAnyRoute(projectId, anchorNodeId)) {
            throw new IllegalArgumentException(
                    "Anchor node belongs to a route and requires an explicit routeId: " + anchorNodeId);
        }

        List<UUID> lineage = resolveLineage(anchorNodeId);
        List<UUID> includedAnswerIds = routeId == null
                ? List.of()
                : routeHistoryResolver
                        .resolveEffectiveAnswers(routeId, lineage)
                        .stream().map(answer -> answer.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(patch -> patch.id()).toList();
        // Without an explicit route there is no route to read from: exclude
        // every route so the context stays the anchor itself, never the whole
        // workspace.
        List<UUID> excludedRouteIds = routeId == null
                ? routeRepository.findByProject(projectId).stream()
                        .map(Route::id).toList()
                : routeRepository.findByProject(projectId).stream()
                        .map(Route::id)
                        .filter(id -> !id.equals(routeId))
                        .toList();

        // B7 — bounded 1-hop semantic context. Only ACTIVE relations of the
        // configured types that touch the anchor enter the context; the related
        // canonical node ids are recorded separately and never pollute the
        // lineage. No recursion: neighbours of the related nodes are excluded.
        List<ContextRelation> relations = resolveSemanticRelations(projectId, anchorNodeId);
        List<UUID> relatedNodeIds = relations.stream()
                .map(r -> r.sourceNodeId().equals(anchorNodeId) ? r.targetNodeId() : r.sourceNodeId())
                .distinct()
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "userQuestion", userQuestion == null ? "" : userQuestion));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.NODE_QUERY, lineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, relations, specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(
                Ids.random(), projectId, routeId, anchorNodeId,
                ContextOperationType.NODE_QUERY, lineage, includedAnswerIds,
                includedPatchIds, excludedRouteIds, relatedNodeIds, relations,
                specialInputs, contextHash, Instant.now());
        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * B4.3 helper: true when the anchor node sits on the canonical lineage of any
     * route in the project. Bounded to the project's routes (never a full
     * workspace scan); used to reject a routeless query whose anchor really
     * belongs to a route.
     */
    private boolean isAnchorMemberOfAnyRoute(UUID projectId, UUID anchorNodeId) {
        for (Route candidate : routeRepository.findByProject(projectId)) {
            if (resolveLineage(candidate.tipNodeId()).contains(anchorNodeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * B7 helper: the bounded 1-hop semantic context for an anchor. Returns the
     * ACTIVE relations of the configured types that touch the anchor, in
     * canonical stored direction, with no recursion.
     */
    private List<ContextRelation> resolveSemanticRelations(UUID projectId, UUID anchorNodeId) {
        List<ContextRelation> result = new ArrayList<>();
        for (NodeRelation relation : nodeRelationRepository.findActiveTouchingNode(projectId, anchorNodeId)) {
            if (!SEMANTIC_RELATION_TYPES.contains(relation.relationType())) {
                continue;
            }
            result.add(new ContextRelation(
                    relation.sourceNodeId(), relation.targetNodeId(), relation.relationType().code()));
        }
        return List.copyOf(result);
    }

    /**
     * Resolves a route's root-to-tip lineage by following {@code parentNodeId}
     * pointers from the tip upward. The chain is deterministic: a route's
     * context is exactly this chain, and sibling or replacement nodes never
     * appear in it.
     */
    private List<UUID> resolveLineage(UUID tipNodeId) {
        return routeHistoryResolver.resolveLineage(tipNodeId);
    }

    private String computeHash(ContextOperationType operationType,
                               List<UUID> nodeIds,
                               List<UUID> answerIds,
                               List<UUID> patchIds,
                               List<UUID> excludedRouteIds,
                               List<ContextRelation> relations,
                               Map<String, Object> specialInputs) {
        List<UUID> sortedNodes = new ArrayList<>(nodeIds);
        List<UUID> sortedAnswers = new ArrayList<>(answerIds);
        List<UUID> sortedPatches = new ArrayList<>(patchIds);
        List<UUID> sortedExcluded = new ArrayList<>(excludedRouteIds);
        sortedNodes.sort(Comparator.naturalOrder());
        sortedAnswers.sort(Comparator.naturalOrder());
        sortedPatches.sort(Comparator.naturalOrder());
        sortedExcluded.sort(Comparator.naturalOrder());
        List<ContextRelation> sortedRelations = new ArrayList<>(relations == null ? List.of() : relations);
        sortedRelations.sort(Comparator.comparing(ContextRelation::sourceNodeId)
                .thenComparing(ContextRelation::targetNodeId)
                .thenComparing(ContextRelation::relationType));
        Map<String, Object> sortedSpecialInputs = specialInputs == null
                ? Map.of()
                : new TreeMap<>(specialInputs);
        String canonical = operationType.code()
                + "|N:" + sortedNodes
                + "|A:" + sortedAnswers
                + "|P:" + sortedPatches
                + "|X:" + sortedExcluded
                + "|R:" + sortedRelations
                + "|S:" + sortedSpecialInputs;
        return Hashes.sha256Hex(canonical);
    }

    /**
     * Adds frozen project metadata to any operation-specific special inputs.
     * The helper keeps replacement/regenerate inputs extensible while ensuring
     * the title is always part of the exact snapshot and its hash.
     */
    private Map<String, Object> withProjectTitle(Project project,
                                                  Map<String, Object> existing) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("projectTitle", project.title() == null ? "" : project.title());
        if (existing != null) {
            merged.putAll(existing);
        }
        return Map.copyOf(merged);
    }
}
