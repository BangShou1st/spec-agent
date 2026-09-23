package com.specagent.workspace.graph;

import com.specagent.common.AnswerExistencePort;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Undo/Redo as operation-specific compensation over the typed graph
 * operation log.
 *
 * <p>Undo never physically deletes immutable history: node creation is
 * compensated by soft retraction plus route-tip rollback; draft edits restore
 * the prior content; semantic relations are marked retracted; branch routes
 * are soft-deleted and restorable. Redo re-applies the original logical
 * operation only while its preconditions still hold; intervening work that
 * would conflict makes redo unavailable rather than forcing replay.
 *
 * <p>Linear stack semantics: undo targets the most recent ACTIVE operation;
 * redo targets the most recent UNDONE operation, provided no newer ACTIVE
 * operation was created after it was undone (new work cuts off the redo
 * branch, exactly like a familiar editor undo history).
 *
 * <p>Non-reversible barrier: an ACTIVE non-reversible operation (accepted
 * agent proposals) is an undo-history barrier. Undo never reaches past it —
 * earlier reversible operations stay out of reach while the barrier is the
 * latest ACTIVE operation, because compensating them underneath accepted
 * agent work could silently break the graph invariants the proposal relied
 * on. {@link #canUndo} reports false at a barrier so the UI never offers an
 * undo that would be rejected.
 */
@Service
public class UndoRedoService {

    private final GraphOperationRepository operationRepository;
    private final NodeService nodeService;
    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;
    private final RouteService routeService;
    private final NodeRelationRepository relationRepository;
    private final AnswerExistencePort answerExistencePort;
    private final ProjectRepository projectRepository;
    private final GraphInvariantValidator invariantValidator;
    private final RouteHistoryResolver routeHistoryResolver;

    public UndoRedoService(GraphOperationRepository operationRepository,
                           NodeService nodeService,
                           NodeRepository nodeRepository,
                           RouteRepository routeRepository,
                           RouteService routeService,
                           NodeRelationRepository relationRepository,
                           AnswerExistencePort answerExistencePort,
                           ProjectRepository projectRepository,
                           GraphInvariantValidator invariantValidator,
                           RouteHistoryResolver routeHistoryResolver) {
        this.operationRepository = operationRepository;
        this.nodeService = nodeService;
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
        this.relationRepository = relationRepository;
        this.answerExistencePort = answerExistencePort;
        this.projectRepository = projectRepository;
        this.invariantValidator = invariantValidator;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    public record UndoRedoResult(GraphOperation operation, String description) {
    }

    /**
     * True when the most recent ACTIVE operation is reversible. A
     * non-reversible operation at the top of the stack is an undo barrier:
     * older ACTIVE operations are NOT reachable for undo while it stands, so
     * this returns false even though reversible operations still exist
     * further down the log.
     */
    public boolean canUndo(UUID projectId) {
        return latestByStatus(projectId, GraphOperation.Status.ACTIVE)
                .map(op -> op.reversible())
                .orElse(false);
    }

    /** True when the most-recently-undone operation can still be replayed. */
    public boolean canRedo(UUID projectId) {
        return latestUndoneForRedo(projectId)
                .map(op -> redoNotCutOff(projectId, op))
                .orElse(false);
    }

    @Transactional
    public UndoRedoResult undo(UUID projectId) {
        // Serialize stack mutations for this project: undo/redo and live graph
        // mutations (e.g. createSemanticRelation) all take the same project-row
        // lock, so two concurrent undos can never act on the same operation and
        // an undo can never interleave with a new mutation into a half-applied
        // stack. The node-specific lock for the retraction target is taken
        // later, inside the compensation path, before any retraction decision.
        projectRepository.lockById(projectId);
        GraphOperation operation = latestByStatus(projectId, GraphOperation.Status.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("没有可撤销的操作"));
        if (!operation.reversible()) {
            throw new IllegalStateException("该操作不可撤销: " + operation.type());
        }
        compensate(operation);
        operationRepository.updateStatus(operation.id(), GraphOperation.Status.UNDONE,
                com.specagent.workspace.graph.GraphOperationRepository.nextTimestamp());
        // Re-read so the response reflects the persisted UNDONE status instead
        // of the in-memory ACTIVE object that was just compensated.
        return new UndoRedoResult(operationRepository.findById(operation.id()).orElse(operation),
                describeUndo(operation));
    }

    @Transactional
    public UndoRedoResult redo(UUID projectId) {
        projectRepository.lockById(projectId);
        // Redo targets the most-recently-undone operation (largest undoneAt):
        // exactly like a familiar editor, the last thing you undid is the first
        // thing you redo. createdAt would not order multi-undo correctly.
        GraphOperation operation = latestUndoneForRedo(projectId)
                .orElseThrow(() -> new IllegalStateException("没有可恢复的操作"));
        if (!redoNotCutOff(projectId, operation)) {
            throw new IllegalStateException("撤销后产生了新操作，无法恢复该历史状态");
        }
        replay(operation);
        operationRepository.updateStatus(operation.id(), GraphOperation.Status.ACTIVE,
                com.specagent.workspace.graph.GraphOperationRepository.nextTimestamp());
        return new UndoRedoResult(operationRepository.findById(operation.id()).orElse(operation),
                describeRedo(operation));
    }

    // ------------------------------------------------------------------
    // Undo compensation per operation type
    // ------------------------------------------------------------------

    private void compensate(GraphOperation operation) {
        switch (operation.type()) {
            case CREATE_DRAFT_NODE, APPEND_CONTINUATION, ATTACH_RESOURCE -> compensateNodeCreation(operation);
            case CREATE_BRANCH_AND_APPEND -> compensateBranchCreation(operation);
            case CONNECT_FLOATING_NODE -> compensateConnect(operation);
            case DISCONNECT_NODE -> compensateDisconnect(operation);
            case EDIT_DRAFT_NODE -> compensateDraftEdit(operation);
            case CREATE_SEMANTIC_RELATION -> compensateRelation(operation);
            case SET_KNOWLEDGE_STATUS -> compensateKnowledgeStatus(operation);
            case ROUTE_FORK, ROUTE_START -> compensateStandaloneRouteCreation(operation);
            case ROUTE_REANSWER -> compensateReanswerRoute(operation);
            case ROUTE_REGENERATE -> compensateRegenerate(operation);
            case ROUTE_LIFECYCLE -> compensateRouteLifecycle(operation);
            case ACCEPT_AGENT_PROPOSAL ->
                    throw new IllegalStateException("接受的提案操作不在可撤销范围内: " + operation.id());
        }
    }

    /**
     * Undo of "connect a floating node": the node is detached again — it keeps
     * existing with its content, it only loses route membership. Legal only
     * while nothing was appended after it, so lineage history is never orphaned.
     *
     * <p>Two connect shapes exist: the node became the tip (empty route or a
     * knowledge-only head), or it hangs below the unchanged INTERACTION tip as
     * provenance. The recorded {@code tipAdvanced} ref distinguishes them;
     * legacy records (before the ref existed) always advanced the tip, so the
     * current tip state settles the ambiguity fail-closed.
     */
    private void compensateConnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
        nodeRepository.lockById(nodeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during undo: " + routeId));
        boolean tipAdvanced = tipAdvancedOf(operation, route, nodeId);
        if (nodeRepository.existsActiveByParentNodeId(nodeId)) {
            throw new IllegalStateException("节点已有后续内容，请先处理其下游节点");
        }
        UUID previousTipNodeId = optionalUuid(operation.beforeRefs(), "previousTipNodeId");
        if (!tipAdvanced) {
            // Provenance-only connect: the tip never moved, detaching the
            // parent pointer alone fully compensates the operation.
            if (!java.util.Objects.equals(route.tipNodeId(), previousTipNodeId)) {
                throw new IllegalStateException("路线末端已变化，无法撤销这次接入");
            }
            nodeRepository.updateParent(nodeId, null, Instant.now());
            return;
        }
        if (route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("路线末端已变化，无法撤销这次接入");
        }
        Instant now = Instant.now();
        nodeRepository.updateParent(nodeId, null, now);
        if (previousTipNodeId == null) {
            routeRepository.clearTipAndRoot(routeId, now);
        } else {
            routeRepository.updateTipAndRoot(routeId, previousTipNodeId, route.rootNodeId(), now);
        }
    }

    /**
     * Connect operations recorded {@code tipAdvanced} from the first kind-aware
     * connect onward; older records always advanced the tip, so a missing ref
     * means "advanced" unless the current tip says otherwise (fail-closed
     * inference for logs written before the fix).
     */
    private boolean tipAdvancedOf(GraphOperation operation, Route route, UUID nodeId) {
        Object recorded = operation.afterRefs().get("tipAdvanced");
        if (recorded instanceof Boolean b) {
            return b;
        }
        return route.tipNodeId() != null && route.tipNodeId().equals(nodeId);
    }

    /**
     * Undo of "detach": the node is re-attached the way it was — as the route
     * tip (tip detach), or hung back below the lineage (provenance detach,
     * tip untouched). Legacy records were always tip detaches.
     */
    private void compensateDisconnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.beforeRefs(), "parentId");
        boolean tipDetached = operation.afterRefs().get("tipDetached") instanceof Boolean b
                ? b
                : true; // legacy records always detached the tip
        Node node = requireActiveNode(operation.projectId(), nodeId);
        nodeRepository.lockById(nodeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during undo: " + routeId));
        Instant now = Instant.now();
        if (!tipDetached) {
            // Provenance detach: the tip never moved; require the old parent
            // to still sit on the lineage, then restore the parent pointer.
            if (node.parentNodeId() != null) {
                throw new IllegalStateException("节点已接入路线，无法撤销这次断开");
            }
            List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
            if (parentId == null || !lineage.contains(parentId)) {
                throw new IllegalStateException("路线谱系已变化，无法撤销这次断开");
            }
            nodeRepository.updateParent(nodeId, parentId, now);
            return;
        }
        if (!java.util.Objects.equals(route.tipNodeId(), parentId)) {
            throw new IllegalStateException("路线末端已变化，无法撤销这次断开");
        }
        nodeRepository.updateParent(nodeId, parentId, now);
        routeRepository.updateTipAndRoot(routeId, nodeId,
                route.rootNodeId() != null ? route.rootNodeId() : nodeId, now);
    }

    private void compensateNodeCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        // routeId is absent for floating creations (created without any route);
        // a floating draft never touched the route tip/root.
        UUID routeId = optionalUuid(operation.afterRefs(), "routeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
        // Lock the node row before deciding retraction, mirroring
        // AnswerService.finalizeAnswer's lock-first pattern: the retractability
        // checks below then observe an authoritative, race-free node state, so
        // an in-flight answer finalization or graph mutation on the same node
        // cannot be lost (or win a lost-update race) once we commit the
        // retraction.
        nodeRepository.lockById(nodeId);
        requireRetractable(operation.projectId(), node, routeId);

        nodeService.setRetracted(nodeId, true);
        if (isFloatingCreation(operation)) {
            // A floating draft never touched the route tip/root; retraction
            // alone fully compensates its creation.
            return;
        }
        UUID parentId = node.parentNodeId();
        if (parentId == null) {
            routeRepository.clearTipAndRoot(routeId, Instant.now());
        } else {
            Route route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalStateException("Route missing during undo: " + routeId));
            routeRepository.updateTipAndRoot(routeId, parentId, route.rootNodeId(), Instant.now());
        }
    }

    private void compensateBranchCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
        // Lock the node row before deciding retraction (see compensateNodeCreation).
        nodeRepository.lockById(nodeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Branch route missing during undo: " + routeId));
        if (route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("分支路线已继续推进，无法撤销创建的节点");
        }
        requireRetractable(operation.projectId(), node, routeId);

        // Restore the active pointer first (legacy records have no explicit
        // previous-active ref; the branch's source route is the safe fallback),
        // then soft-delete via the bare transition core — compensations must
        // not append lifecycle operations of their own.
        restoreActivePointer(operation, optionalUuid(operation.beforeRefs(), "sourceRouteId"));
        nodeService.setRetracted(nodeId, true);
        routeService.transitionLifecycle(operation.projectId(), routeId, RouteLifecycleStatus.DELETED);
    }

    private void compensateDraftEdit(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        Node node = requireActiveNode(operation.projectId(), nodeId);
        if (!node.isUserEditableDraft()) {
            throw new IllegalStateException("节点已不再是可编辑草稿，无法恢复旧内容");
        }
        nodeService.reviseUserDraft(operation.projectId(), nodeId,
                (String) operation.beforeRefs().get("subtype"),
                castContent(operation.beforeRefs().get("content")));
    }

    private void compensateRelation(GraphOperation operation) {
        UUID relationId = requireUuid(operation.afterRefs(), "relationId");
        NodeRelation relation = relationRepository.findById(relationId)
                .orElseThrow(() -> new IllegalStateException("Relation missing during undo: " + relationId));
        if (!relation.isActive()) {
            throw new IllegalStateException("关系已被撤销，无法重复撤销");
        }
        relationRepository.updateStatus(relationId, NodeRelation.Status.RETRACTED, Instant.now());
    }

    private void compensateKnowledgeStatus(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        Node node = requireActiveNode(operation.projectId(), nodeId);
        KnowledgeStatus after = KnowledgeStatus.fromCode(
                String.valueOf(operation.afterRefs().get("status")));
        if (node.knowledgeStatus() != after) {
            throw new IllegalStateException("知识状态已再次变化，无法直接撤销该次转换");
        }
        nodeService.setKnowledgeStatus(operation.projectId(), nodeId,
                KnowledgeStatus.fromCode(String.valueOf(operation.beforeRefs().get("status"))));
    }

    // ------------------------------------------------------------------
    // Route-operation compensations
    // ------------------------------------------------------------------

    /**
     * Undo of ROUTE_FORK / ROUTE_START: the new route is soft-deleted — but
     * only while it still sits exactly where creation left it (no continuation
     * advanced its tip). The node(s) the route points at are shared or floating
     * and are never touched.
     */
    private void compensateStandaloneRouteCreation(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID tipNodeId = optionalUuid(operation.afterRefs(), "tipNodeId");
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException("路线状态已变化，无法撤销该路线操作");
        }
        if (tipNodeId == null || !tipNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("路线已继续推进，无法撤销该路线操作");
        }
        restoreActivePointer(operation, null);
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.DELETED);
    }

    /** Redo of ROUTE_FORK / ROUTE_START: reopen the soft-deleted route. */
    private void replayStandaloneRouteCreation(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID tipNodeId = optionalUuid(operation.afterRefs(), "tipNodeId");
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.DELETED) {
            throw new IllegalStateException("路线状态已变化，无法恢复该路线操作");
        }
        if (tipNodeId == null || !tipNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("路线已继续推进，无法恢复该路线操作");
        }
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.OPEN);
        routeService.setActiveRoutePointer(operation.projectId(), routeId);
    }

    /** Undo of ROUTE_REANSWER: retract the cloned question and soft-delete its route. */
    private void compensateReanswerRoute(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID clonedNodeId = requireUuid(operation.afterRefs(), "clonedNodeId");
        Node node = requireActiveNode(operation.projectId(), clonedNodeId);
        nodeRepository.lockById(clonedNodeId);
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN
                || !clonedNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("重新回答路线已继续推进，无法撤销");
        }
        requireRetractable(operation.projectId(), node, routeId);
        restoreActivePointer(operation, optionalUuid(operation.beforeRefs(), "sourceRouteId"));
        nodeService.setRetracted(clonedNodeId, true);
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.DELETED);
    }

    /** Redo of ROUTE_REANSWER: reopen the route and un-retract the clone. */
    private void replayReanswerRoute(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID clonedNodeId = requireUuid(operation.afterRefs(), "clonedNodeId");
        Node node = nodeRepository.findById(clonedNodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + clonedNodeId));
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.DELETED
                || !clonedNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("重新回答路线状态已变化，无法恢复");
        }
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }
        requireRetractable(operation.projectId(), node, routeId);
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.OPEN);
        nodeService.setRetracted(clonedNodeId, false);
        routeService.setActiveRoutePointer(operation.projectId(), routeId);
    }

    /** Undo of ROUTE_REGENERATE: retract the replacement and reopen the source route. */
    private void compensateRegenerate(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID replacementNodeId = requireUuid(operation.afterRefs(), "replacementNodeId");
        UUID sourceRouteId = requireUuid(operation.afterRefs(), "sourceRouteId");
        Node node = requireActiveNode(operation.projectId(), replacementNodeId);
        nodeRepository.lockById(replacementNodeId);
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN
                || !replacementNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("换题路线已继续推进，无法撤销");
        }
        requireRetractable(operation.projectId(), node, routeId);
        boolean sourceWasOpen = Boolean.TRUE.equals(operation.beforeRefs().get("sourceWasOpen"));
        if (sourceWasOpen) {
            Route source = requireRoute(operation.projectId(), sourceRouteId);
            if (source.lifecycleStatus() != RouteLifecycleStatus.SUPERSEDED) {
                throw new IllegalStateException("来源路线状态已变化，无法撤销换题");
            }
        }
        restoreActivePointer(operation, optionalUuid(operation.beforeRefs(), "sourceRouteId"));
        nodeService.setRetracted(replacementNodeId, true);
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.DELETED);
        if (sourceWasOpen) {
            // SUPERSEDED → OPEN is a legal reverse transition.
            routeService.transitionLifecycle(operation.projectId(), sourceRouteId,
                    RouteLifecycleStatus.OPEN);
        }
    }

    /** Redo of ROUTE_REGENERATE: reopen the replacement, re-retract nothing, re-supersede. */
    private void replayRegenerate(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID replacementNodeId = requireUuid(operation.afterRefs(), "replacementNodeId");
        UUID sourceRouteId = requireUuid(operation.afterRefs(), "sourceRouteId");
        Node node = nodeRepository.findById(replacementNodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + replacementNodeId));
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.DELETED
                || !replacementNodeId.equals(route.tipNodeId())) {
            throw new IllegalStateException("换题路线状态已变化，无法恢复");
        }
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }
        requireRetractable(operation.projectId(), node, routeId);
        boolean sourceWasOpen = Boolean.TRUE.equals(operation.beforeRefs().get("sourceWasOpen"));
        if (sourceWasOpen) {
            Route source = requireRoute(operation.projectId(), sourceRouteId);
            if (source.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
                throw new IllegalStateException("来源路线状态已变化，无法恢复换题");
            }
            // OPEN → SUPERSEDED is reserved for replacement commits (the state
            // machine forbids it as a user lifecycle command), so re-apply the
            // supersession directly like the live commit path does.
            routeRepository.updateLifecycle(sourceRouteId, RouteLifecycleStatus.SUPERSEDED,
                    Instant.now());
        }
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.OPEN);
        nodeService.setRetracted(replacementNodeId, false);
        routeService.setActiveRoutePointer(operation.projectId(), routeId);
    }

    /**
     * Undo of ROUTE_LIFECYCLE: apply the reverse transition (fail-closed
     * against the lifecycle state machine — e.g. a restored SUPERSEDED route
     * cannot be un-restored back to SUPERSEDED) and restore the recorded
     * active-route pointer.
     */
    private void compensateRouteLifecycle(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        RouteLifecycleStatus toStatus = RouteLifecycleStatus.fromCode(
                String.valueOf(operation.afterRefs().get("toStatus")));
        RouteLifecycleStatus fromStatus = RouteLifecycleStatus.fromCode(
                String.valueOf(operation.beforeRefs().get("fromStatus")));
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != toStatus) {
            throw new IllegalStateException("路线状态已再次变化，无法撤销该次状态变更");
        }
        routeService.transitionLifecycle(operation.projectId(), routeId, fromStatus);
        restoreActivePointer(operation, null);
    }

    /** Redo of ROUTE_LIFECYCLE: re-apply the forward transition. */
    private void replayRouteLifecycle(GraphOperation operation) {
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        RouteLifecycleStatus toStatus = RouteLifecycleStatus.fromCode(
                String.valueOf(operation.afterRefs().get("toStatus")));
        RouteLifecycleStatus fromStatus = RouteLifecycleStatus.fromCode(
                String.valueOf(operation.beforeRefs().get("fromStatus")));
        Route route = requireRoute(operation.projectId(), routeId);
        if (route.lifecycleStatus() != fromStatus) {
            throw new IllegalStateException("路线状态已再次变化，无法重放该次状态变更");
        }
        routeService.transitionLifecycle(operation.projectId(), routeId, toStatus);
        if (toStatus == RouteLifecycleStatus.OPEN) {
            routeService.setActiveRoutePointer(operation.projectId(), routeId);
        } else {
            routeRepository.findById(routeId)
                    .filter(r -> projectActiveRouteId(operation.projectId())
                            .map(r.id()::equals)
                            .orElse(false))
                    .ifPresent(r -> projectRepository.updateActiveRoute(
                            operation.projectId(), null, Instant.now()));
        }
    }

    /** Sets the active-route pointer back to the recorded value (no-op when equal). */
    private void restoreActivePointer(GraphOperation operation, UUID fallbackRouteId) {
        UUID previous = optionalUuid(operation.beforeRefs(), "previousActiveRouteId");
        if (previous == null) {
            previous = fallbackRouteId;
        }
        UUID current = projectActiveRouteId(operation.projectId()).orElse(null);
        if (java.util.Objects.equals(current, previous)) {
            return;
        }
        routeService.setActiveRoutePointer(operation.projectId(), previous);
    }

    private java.util.Optional<UUID> projectActiveRouteId(UUID projectId) {
        return projectRepository.findById(projectId).map(p -> p.activeRouteId());
    }

    private Route requireRoute(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalStateException("Route belongs to another project: " + routeId);
        }
        return route;
    }

    // ------------------------------------------------------------------
    // Redo replay per operation type (preconditions checked first)
    // ------------------------------------------------------------------

    private void replay(GraphOperation operation) {
        switch (operation.type()) {
            case CREATE_DRAFT_NODE, APPEND_CONTINUATION, ATTACH_RESOURCE -> replayNodeCreation(operation);
            case CREATE_BRANCH_AND_APPEND -> replayBranchCreation(operation);
            case CONNECT_FLOATING_NODE -> replayConnect(operation);
            case DISCONNECT_NODE -> replayDisconnect(operation);
            case EDIT_DRAFT_NODE -> replayDraftEdit(operation);
            case CREATE_SEMANTIC_RELATION -> replayRelation(operation);
            case SET_KNOWLEDGE_STATUS -> replayKnowledgeStatus(operation);
            case ROUTE_FORK, ROUTE_START -> replayStandaloneRouteCreation(operation);
            case ROUTE_REANSWER -> replayReanswerRoute(operation);
            case ROUTE_REGENERATE -> replayRegenerate(operation);
            case ROUTE_LIFECYCLE -> replayRouteLifecycle(operation);
            case ACCEPT_AGENT_PROPOSAL ->
                    throw new IllegalStateException("接受的提案操作无法重放: " + operation.id());
        }
    }

    /**
     * Redo of a connect: re-attach the same node as the tip (or below the
     * unchanged tip for provenance connects), but only while the route still
     * sits exactly where the undo left it — intervening work fails closed
     * instead of silently rebasing the lineage.
     */
    private void replayConnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.afterRefs(), "parentId");
        UUID previousTipNodeId = optionalUuid(operation.beforeRefs(), "previousTipNodeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
        if (node.parentNodeId() != null) {
            throw new IllegalStateException("节点已接入路线，无法重复恢复");
        }
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during redo: " + routeId));
        boolean tipAdvanced = operation.afterRefs().get("tipAdvanced") instanceof Boolean b
                ? b
                : true; // legacy records always advanced the tip
        if (!java.util.Objects.equals(route.tipNodeId(), previousTipNodeId)) {
            throw new IllegalStateException("路线末端已变化，无法恢复这次接入");
        }
        Instant now = Instant.now();
        nodeRepository.updateParent(nodeId, parentId, now);
        if (tipAdvanced) {
            routeRepository.updateTipAndRoot(routeId, nodeId,
                    route.rootNodeId() != null ? route.rootNodeId() : nodeId, now);
        }
    }

    /** Redo of a detach: detach again, requiring the same state. */
    private void replayDisconnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.beforeRefs(), "parentId");
        boolean tipDetached = operation.afterRefs().get("tipDetached") instanceof Boolean b
                ? b
                : true; // legacy records always detached the tip
        Node node = requireActiveNode(operation.projectId(), nodeId);
        if (!java.util.Objects.equals(node.parentNodeId(), parentId)) {
            throw new IllegalStateException("节点归属已变化，无法重复断开");
        }
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during redo: " + routeId));
        Instant now = Instant.now();
        if (!tipDetached) {
            List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
            if (parentId == null || !lineage.contains(parentId)) {
                throw new IllegalStateException("路线谱系已变化，无法恢复这次断开");
            }
            nodeRepository.updateParent(nodeId, null, now);
            return;
        }
        if (route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("路线末端已变化，无法恢复这次断开");
        }
        nodeRepository.updateParent(nodeId, null, now);
        if (parentId == null) {
            routeRepository.clearTipAndRoot(routeId, now);
        } else {
            routeRepository.updateTipAndRoot(routeId, parentId, route.rootNodeId(), now);
        }
    }

    private void replayNodeCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        // routeId is absent for floating creations; floating restore never
        // touches the route tip/root.
        UUID routeId = optionalUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.afterRefs(), "parentId");
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + nodeId));
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }

        if (isFloatingCreation(operation)) {
            // Floating drafts stay disconnected: restoring them must not
            // touch the route tip/root or require a specific tip state, and
            // must never consult a route that the creation never referenced.
            requireRetractable(operation.projectId(), node, routeId);
            nodeService.setRetracted(nodeId, false);
            return;
        }
        requireRetractable(operation.projectId(), node, routeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during redo: " + routeId));
        UUID expectedTip = parentId != null ? parentId : null;
        if (!java.util.Objects.equals(route.tipNodeId(), expectedTip)) {
            throw new IllegalStateException("路线末端已变化，无法恢复该节点");
        }

        nodeService.setRetracted(nodeId, false);
        if (parentId == null) {
            routeRepository.updateTipAndRoot(routeId, nodeId, nodeId, Instant.now());
        } else {
            routeRepository.updateTipAndRoot(routeId, nodeId, route.rootNodeId(), Instant.now());
        }
    }

    /** True when the recorded creation was a standalone (floating) draft. */
    private boolean isFloatingCreation(GraphOperation operation) {
        return Boolean.TRUE.equals(operation.afterRefs().get("floating"));
    }

    private void replayBranchCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + nodeId));
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }
        requireRetractable(operation.projectId(), node, routeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Branch route missing during redo: " + routeId));
        // Undo leaves the soft-deleted route's tip at the created node; redo
        // is possible only while that tip never advanced past it.
        if (route.lifecycleStatus() != RouteLifecycleStatus.DELETED
                || route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("分支路线状态已变化，无法恢复");
        }

        routeService.transitionLifecycle(operation.projectId(), routeId, RouteLifecycleStatus.OPEN);
        nodeService.setRetracted(nodeId, false);
        routeRepository.updateTipAndRoot(routeId, nodeId, route.rootNodeId(), Instant.now());
        routeService.setActiveRoutePointer(operation.projectId(), routeId);
    }

    private void replayDraftEdit(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        Node node = requireActiveNode(operation.projectId(), nodeId);
        if (!node.isUserEditableDraft()) {
            throw new IllegalStateException("节点已不再是可编辑草稿，无法重放编辑");
        }
        nodeService.reviseUserDraft(operation.projectId(), nodeId,
                (String) operation.afterRefs().get("subtype"),
                castContent(operation.afterRefs().get("content")));
    }

    /**
     * Re-activates the SAME relation row that undo retracted, but only after
     * re-running the identical invariant boundary the live creation path uses
     * ({@code GraphCommandService.createSemanticRelation} →
     * {@link GraphInvariantValidator#validateRelationCreation}). Redo must never
     * bypass validation or mint a new relation identity: if intervening work
     * (a conflicting active relation, a dependency cycle, a retracted endpoint,
     * or a cross-project/self reference) would now invalidate the replay, it
     * fails closed rather than silently rebasing the graph.
     */
    private void replayRelation(GraphOperation operation) {
        UUID relationId = requireUuid(operation.afterRefs(), "relationId");
        NodeRelation relation = relationRepository.findById(relationId)
                .orElseThrow(() -> new IllegalStateException("Relation missing during redo: " + relationId));
        if (relation.isActive()) {
            throw new IllegalStateException("关系已恢复，无法重复恢复");
        }
        UUID sourceNodeId = requireUuid(operation.afterRefs(), "sourceNodeId");
        UUID targetNodeId = requireUuid(operation.afterRefs(), "targetNodeId");
        NodeRelationType type = NodeRelationType.fromCode(
                String.valueOf(operation.afterRefs().get("relationType")));
        // Serialize against concurrent relation mutations within the project,
        // exactly like the live creation path.
        projectRepository.lockById(operation.projectId());
        // Endpoints exist / not retracted / same project / not self, plus the
        // DEPENDS_ON + DERIVED_FROM DAG cycle check against the CURRENT graph.
        invariantValidator.validateRelationCreation(operation.projectId(), sourceNodeId, targetNodeId, type);
        // No active duplicate of this canonical relation may already exist: if
        // intervening work created one, replaying would violate the unique
        // backstop, so fail closed instead of rebasing. The canonical-pair
        // lookup mirrors the live de-duplication (symmetric types normalized).
        if (relationRepository.findActiveByCanonicalPair(operation.projectId(), sourceNodeId, targetNodeId, type)
                .map(r -> !r.id().equals(relationId))
                .orElse(false)) {
            throw new IllegalStateException("关系重做被拒：当前图已存在相同语义关系，不能重复恢复");
        }
        relationRepository.updateStatus(relationId, NodeRelation.Status.ACTIVE, Instant.now());
    }

    private void replayKnowledgeStatus(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        Node node = requireActiveNode(operation.projectId(), nodeId);
        KnowledgeStatus before = KnowledgeStatus.fromCode(
                String.valueOf(operation.beforeRefs().get("status")));
        if (node.knowledgeStatus() != before) {
            throw new IllegalStateException("知识状态已再次变化，无法重放该次转换");
        }
        nodeService.setKnowledgeStatus(operation.projectId(), nodeId,
                KnowledgeStatus.fromCode(String.valueOf(operation.afterRefs().get("status"))));
    }

    // ------------------------------------------------------------------
    // Shared preconditions
    // ------------------------------------------------------------------

    /**
     * A created node can be retracted only while it is a leaf with no
     * immutable answers and no other route pointing at it as tip; otherwise
     * downstream history would be silently orphaned.
     */
    private void requireRetractable(UUID projectId, Node node, UUID owningRouteId) {
        // Only live (non-retracted) descendants block retraction. A child that
        // was itself already undone (soft-retracted) does NOT block undoing its
        // parent — otherwise the second undo would be permanently rejected and
        // the linear stack would never unwind past it.
        if (nodeRepository.existsActiveByParentNodeId(node.id())) {
            throw new IllegalStateException("节点已有后续内容，请先处理其下游节点");
        }
        if (answerExistencePort.nodeHasFinalizedAnswer(node.id())) {
            throw new IllegalStateException("节点已有不可变回答，无法撤销创建");
        }
        List<UUID> tipRoutes = routeRepository.findRouteIdsByTipNodeId(node.id());
        if (tipRoutes.stream().anyMatch(routeId -> !routeId.equals(owningRouteId))) {
            throw new IllegalStateException("节点已被其他路线引用，无法撤销创建");
        }
    }

    private Node requireActiveNode(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalStateException("Node belongs to another project: " + nodeId);
        }
        return node;
    }

    private java.util.Optional<GraphOperation> latestByStatus(UUID projectId, GraphOperation.Status status) {
        return operationRepository.findByProject(projectId).stream()
                .filter(op -> op.status() == status)
                .max(Comparator.comparing(GraphOperation::createdAt)
                        .thenComparing(op -> op.id().toString()));
    }

    /**
     * Redo target: the most-recently-undone operation — the UNDONE operation
     * with the greatest {@code undoneAt} (ties broken by id for determinism).
     * {@code undoneAt} is populated only when an operation transitions to
     * UNDONE, so it captures the true undo order; {@code createdAt} would not
     * distinguish a third undo from an earlier one. UNDONE ops without an
     * {@code undoneAt} (none are produced today) are ignored.
     */
    private java.util.Optional<GraphOperation> latestUndoneForRedo(UUID projectId) {
        return operationRepository.findByProject(projectId).stream()
                .filter(op -> op.status() == GraphOperation.Status.UNDONE && op.undoneAt() != null)
                .max(Comparator.comparing(GraphOperation::undoneAt)
                        .thenComparing(op -> op.id().toString()));
    }

    /**
     * New ACTIVE work created after the operation was undone cuts off its
     * redo branch; the user must re-issue the operation explicitly.
     */
    private boolean redoNotCutOff(UUID projectId, GraphOperation undone) {
        Instant undoneAt = undone.undoneAt() == null ? Instant.EPOCH : undone.undoneAt();
        return operationRepository.findByProject(projectId).stream()
                .noneMatch(op -> op.status() == GraphOperation.Status.ACTIVE
                        && op.createdAt().isAfter(undoneAt)
                        && !op.id().equals(undone.id()));
    }

    // ------------------------------------------------------------------
    // Ref helpers
    // ------------------------------------------------------------------

    private UUID requireUuid(Map<String, Object> refs, String key) {
        Object value = refs.get(key);
        if (value == null) {
            throw new IllegalStateException("Operation ref missing: " + key);
        }
        return UUID.fromString(String.valueOf(value));
    }

    private UUID optionalUuid(Map<String, Object> refs, String key) {
        Object value = refs.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castContent(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("Operation content ref has unexpected shape");
    }

    private String describeUndo(GraphOperation operation) {
        return switch (operation.type()) {
            case CREATE_DRAFT_NODE -> "已撤销：创建草稿节点";
            case EDIT_DRAFT_NODE -> "已撤销：编辑草稿节点";
            case APPEND_CONTINUATION -> "已撤销：继续探索";
            case ATTACH_RESOURCE -> "已撤销：添加资源";
            case CONNECT_FLOATING_NODE -> "已撤销：接入路线";
            case DISCONNECT_NODE -> "已撤销：断开路线";
            case CREATE_BRANCH_AND_APPEND -> "已撤销：新建分支";
            case CREATE_SEMANTIC_RELATION -> "已撤销：添加语义关系";
            case SET_KNOWLEDGE_STATUS -> "已撤销：知识状态变更";
            case ROUTE_FORK -> "已撤销：新建分支";
            case ROUTE_REANSWER -> "已撤销：重新回答路线";
            case ROUTE_REGENERATE -> "已撤销：换题";
            case ROUTE_START -> "已撤销：新路线";
            case ROUTE_LIFECYCLE -> "已撤销：路线状态变更";
            case ACCEPT_AGENT_PROPOSAL -> "已撤销：接受提案";
        };
    }

    private String describeRedo(GraphOperation operation) {
        return switch (operation.type()) {
            case CREATE_DRAFT_NODE -> "已恢复：创建草稿节点";
            case EDIT_DRAFT_NODE -> "已恢复：编辑草稿节点";
            case APPEND_CONTINUATION -> "已恢复：继续探索";
            case ATTACH_RESOURCE -> "已恢复：添加资源";
            case CONNECT_FLOATING_NODE -> "已恢复：接入路线";
            case DISCONNECT_NODE -> "已恢复：断开路线";
            case CREATE_BRANCH_AND_APPEND -> "已恢复：新建分支";
            case CREATE_SEMANTIC_RELATION -> "已恢复：添加语义关系";
            case SET_KNOWLEDGE_STATUS -> "已恢复：知识状态变更";
            case ROUTE_FORK -> "已恢复：新建分支";
            case ROUTE_REANSWER -> "已恢复：重新回答路线";
            case ROUTE_REGENERATE -> "已恢复：换题";
            case ROUTE_START -> "已恢复：新路线";
            case ROUTE_LIFECYCLE -> "已恢复：路线状态变更";
            // Unreachable: an ACCEPT_AGENT_PROPOSAL can never be in UNDONE
            // state because undo rejects it; kept for switch exhaustiveness.
            case ACCEPT_AGENT_PROPOSAL -> "已恢复：接受提案";
        };
    }
}
