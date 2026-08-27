package com.specagent.graph;

import com.specagent.answer.AnswerRepository;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteInheritedAnswer;
import com.specagent.route.RouteInheritedAnswerRepository;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
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
    private final AnswerRepository answerRepository;
    private final ProjectRepository projectRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RouteInheritedAnswerRepository routeInheritedAnswerRepository;

    public UndoRedoService(GraphOperationRepository operationRepository,
                           NodeService nodeService,
                           NodeRepository nodeRepository,
                           RouteRepository routeRepository,
                           RouteService routeService,
                           NodeRelationRepository relationRepository,
                           AnswerRepository answerRepository,
                           ProjectRepository projectRepository,
                           RouteHistoryResolver routeHistoryResolver,
                           RouteInheritedAnswerRepository routeInheritedAnswerRepository) {
        this.operationRepository = operationRepository;
        this.nodeService = nodeService;
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
        this.relationRepository = relationRepository;
        this.answerRepository = answerRepository;
        this.projectRepository = projectRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.routeInheritedAnswerRepository = routeInheritedAnswerRepository;
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

    /** True when the most recent UNDONE operation can still be replayed. */
    public boolean canRedo(UUID projectId) {
        return latestByStatus(projectId, GraphOperation.Status.UNDONE)
                .map(op -> redoNotCutOff(projectId, op))
                .orElse(false);
    }

    @Transactional
    public UndoRedoResult undo(UUID projectId) {
        GraphOperation operation = latestByStatus(projectId, GraphOperation.Status.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("没有可撤销的操作"));
        if (!operation.reversible()) {
            throw new IllegalStateException("该操作不可撤销: " + operation.type());
        }
        compensate(operation);
        operationRepository.updateStatus(operation.id(), GraphOperation.Status.UNDONE,
                com.specagent.graph.GraphOperationRepository.nextTimestamp());
        // Re-read so the response reflects the persisted UNDONE status instead
        // of the in-memory ACTIVE object that was just compensated.
        return new UndoRedoResult(operationRepository.findById(operation.id()).orElse(operation),
                describeUndo(operation));
    }

    @Transactional
    public UndoRedoResult redo(UUID projectId) {
        GraphOperation operation = latestByStatus(projectId, GraphOperation.Status.UNDONE)
                .orElseThrow(() -> new IllegalStateException("没有可恢复的操作"));
        if (!redoNotCutOff(projectId, operation)) {
            throw new IllegalStateException("撤销后产生了新操作，无法恢复该历史状态");
        }
        replay(operation);
        operationRepository.updateStatus(operation.id(), GraphOperation.Status.ACTIVE,
                com.specagent.graph.GraphOperationRepository.nextTimestamp());
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
            case RESUME_QUESTION_FROM_HISTORY -> compensateResumeRoute(operation);
            case EDIT_DRAFT_NODE -> compensateDraftEdit(operation);
            case CREATE_SEMANTIC_RELATION -> compensateRelation(operation);
            case SET_KNOWLEDGE_STATUS -> compensateKnowledgeStatus(operation);
            case ACCEPT_AGENT_PROPOSAL ->
                    throw new IllegalStateException("接受的提案操作不在可撤销范围内: " + operation.id());
        }
    }

    private void compensateNodeCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
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
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Branch route missing during undo: " + routeId));
        if (route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("分支路线已继续推进，无法撤销创建的节点");
        }
        requireRetractable(operation.projectId(), node, routeId);

        nodeService.setRetracted(nodeId, true);
        // Soft-delete keeps full provenance; restore on redo reopens the route.
        routeService.softDeleteRoute(operation.projectId(), routeId);
    }

    /**
     * Route-only compensation for {@code RESUME_QUESTION_FROM_HISTORY}:
     * soft-delete the resume route and restore the previous Active pointer,
     * but NEVER retract the canonical Question and NEVER delete any answer
     * or any inherited answer reference. Preconditions enforce:
     * <ul>
     *   <li>project.activeRouteId == resumedRouteId (the resume must still
     *       be the active branch — a later user-side activate would mean the
     *       user has moved on, and Undo must not steal that choice);</li>
     *   <li>the resumed route lifecycle is still OPEN;</li>
     *   <li>resumed route tip is still the target (no continuation was
     *       appended after RESUME);</li>
     *   <li>no effective answer exists for the target on the resume route
     *       (immutable Answer is durable history, RESUME cannot be revoked
     *       once it anchors a real answer);</li>
     *   <li>the canonical target Question is not retracted (defense in
     *       depth — no code path here retracts it, but we fail closed if
     *       any other path already did);</li>
     *   <li>the current inherited prefix exactly matches the
     *       {@code expectedInheritedRefs} recorded in the operation's
     *       afterRefs. The expected list MAY be empty when the target was
     *       the first Question ever asked on the source route; an empty
     *       current list is then a match, not a failure.</li>
     *   <li>{@code previousActiveRouteId} (when non-null) still resolves to
     *       a route in {@code OPEN} lifecycle, so the restore is sound.
     *       This is checked BEFORE any mutation: if the previously-active
     *       route has since been archived/superseded/deleted, the
     *       compensation fails closed and refuses to flip Active.</li>
     * </ul>
     * The Active pointer is restored only on exact equality with the
     * recorded {@code previousActiveRouteId} — "X or null" fallbacks are
     * not allowed, because that would silently override a deliberate user
     * activation that happened after the RESUME.
     */
    private void compensateResumeRoute(GraphOperation operation) {
        UUID resumedRouteId = requireUuid(operation.afterRefs(), "routeId");
        UUID targetNodeId = requireUuid(operation.afterRefs(), "targetNodeId");
        UUID previousActiveRouteId = optionalUuid(operation.beforeRefs(), "previousActiveRouteId");
        UUID currentActive = projectRepository.findById(operation.projectId())
                .map(com.specagent.project.Project::activeRouteId)
                .orElse(null);
        if (!resumedRouteId.equals(currentActive)) {
            throw new IllegalStateException(
                    "恢复历史问题操作撤销失败：当前活动路线已不在该 RESUME 路线上");
        }
        Route resumed = routeRepository.findById(resumedRouteId)
                .orElseThrow(() -> new IllegalStateException("Resume route missing during undo: " + resumedRouteId));
        if (resumed.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException("Resume route lifecycle no longer OPEN: " + resumedRouteId);
        }
        if (resumed.tipNodeId() == null || !resumed.tipNodeId().equals(targetNodeId)) {
            throw new IllegalStateException("RESUME 路线已继续推进，无法撤销其创建");
        }
        Node target = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalStateException("Target Question missing: " + targetNodeId));
        if (target.isRetracted()) {
            throw new IllegalStateException("Target Question must not be retracted by RESUME undo: " + targetNodeId);
        }
        List<UUID> lineage = routeHistoryResolver.resolveLineage(targetNodeId);
        boolean alreadyAnswered = routeHistoryResolver
                .resolveEffectiveAnswers(resumedRouteId, lineage).stream()
                .anyMatch(answer -> answer.nodeId().equals(targetNodeId));
        if (alreadyAnswered) {
            throw new IllegalStateException("RESUME 路线上该问题已产生回答，无法撤销 RESUME 创建");
        }
        // Provenance consistency: every ref recorded in afterRefs must still
        // be present in the route_inherited_answers row, and the actual
        // refs must contain nothing extra. Empty expected list is legal
        // (target was the first Question asked on the source route).
        List<RouteInheritedAnswer> actualRefs = routeInheritedAnswerRepository
                .findByBranchRouteId(resumedRouteId);
        verifyInheritedRefProvenance(operation, actualRefs);

        // previousActiveRouteId lifecycle check happens BEFORE mutation.
        // If the previously-active route is no longer OPEN we cannot soundly
        // restore Active, so we fail closed and leave state untouched.
        if (previousActiveRouteId != null) {
            Route previous = routeRepository.findById(previousActiveRouteId).orElse(null);
            if (previous == null
                    || previous.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
                throw new IllegalStateException(
                        "RESUME 撤销失败：先前活动路线已不再 OPEN，无法安全恢复 Active");
            }
        }

        // All preconditions hold. Soft-delete the resume route first; this
        // also handles active-clear when previousActiveRouteId is null.
        routeService.softDeleteRoute(operation.projectId(), resumedRouteId);

        // Restore previous Active pointer only when it was non-null. Exact
        // equality with the recorded previousActiveRouteId is required; an
        // "X or null" fallback would silently override a deliberate user
        // activation.
        if (previousActiveRouteId != null) {
            routeService.setActiveRoute(operation.projectId(), previousActiveRouteId);
        }
    }

    /**
     * Validates that the inherited answer refs currently persisted for a
     * resume route exactly match the snapshot recorded in the operation
     * log. The expected list may be empty. Any drift (missing ref, extra
     * ref, or different identity) is an invariant violation and the
     * compensation fails closed.
     */
    @SuppressWarnings("unchecked")
    private void verifyInheritedRefProvenance(GraphOperation operation,
                                              List<RouteInheritedAnswer> actualRefs) {
        Object expectedRaw = operation.afterRefs().get("expectedInheritedRefs");
        List<Map<String, Object>> expected;
        if (expectedRaw == null) {
            // Older operations recorded before the provenance snapshot was
            // added: fall back to the legacy "refs must be present" check so
            // we do not silently pass compensation on un-tracked data.
            if (actualRefs.isEmpty()) {
                return;
            }
            throw new IllegalStateException(
                    "RESUME 撤销失败：operation 未记录 expectedInheritedRefs，无法验证 provenance");
        }
        if (!(expectedRaw instanceof List<?> expectedList)) {
            throw new IllegalStateException(
                    "RESUME 撤销失败：expectedInheritedRefs 字段类型非法");
        }
        expected = new java.util.ArrayList<>();
        for (Object item : expectedList) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException(
                        "RESUME 撤销失败：expectedInheritedRefs 子项类型非法");
            }
            expected.add((Map<String, Object>) map);
        }
        if (expected.size() != actualRefs.size()) {
            throw new IllegalStateException(
                    "RESUME 撤销失败：expected inherited refs count="
                            + expected.size() + ", actual=" + actualRefs.size());
        }
        for (int i = 0; i < expected.size(); i += 1) {
            Map<String, Object> want = expected.get(i);
            RouteInheritedAnswer got = actualRefs.get(i);
            if (!String.valueOf(want.get("nodeId")).equals(got.nodeId().toString())
                    || !String.valueOf(want.get("answerId")).equals(got.answerId().toString())
                    || !String.valueOf(want.get("ownerRouteId")).equals(got.ownerRouteId().toString())
                    || !Integer.valueOf(String.valueOf(want.get("ordinal"))).equals(got.ordinal())) {
                throw new IllegalStateException(
                        "RESUME 撤销失败：ordinal " + i + " provenance drift");
            }
        }
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
    // Redo replay per operation type (preconditions checked first)
    // ------------------------------------------------------------------

    private void replay(GraphOperation operation) {
        switch (operation.type()) {
            case CREATE_DRAFT_NODE, APPEND_CONTINUATION, ATTACH_RESOURCE -> replayNodeCreation(operation);
            case CREATE_BRANCH_AND_APPEND -> replayBranchCreation(operation);
            case RESUME_QUESTION_FROM_HISTORY -> replayResumeRoute(operation);
            case EDIT_DRAFT_NODE -> replayDraftEdit(operation);
            case CREATE_SEMANTIC_RELATION -> replayRelation(operation);
            case SET_KNOWLEDGE_STATUS -> replayKnowledgeStatus(operation);
            case ACCEPT_AGENT_PROPOSAL ->
                    throw new IllegalStateException("接受的提案操作无法重放: " + operation.id());
        }
    }

    private void replayNodeCreation(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.afterRefs(), "parentId");
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + nodeId));
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }
        requireRetractable(operation.projectId(), node, routeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during redo: " + routeId));

        if (isFloatingCreation(operation)) {
            // Floating drafts stay disconnected: restoring them must not
            // touch the route tip/root or require a specific tip state.
            nodeService.setRetracted(nodeId, false);
            return;
        }
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

        routeService.restoreRoute(operation.projectId(), routeId);
        nodeService.setRetracted(nodeId, false);
        routeRepository.updateTipAndRoot(routeId, nodeId, route.rootNodeId(), Instant.now());
    }

    /**
     * Route-only replay for {@code RESUME_QUESTION_FROM_HISTORY}: restore
     * the soft-deleted resume route, then re-activate it. No node mutation
     * is performed. The canonical Question has never been touched in either
     * direction. Active pointer preconditions: the current Active must
     * match the recorded {@code previousActiveRouteId} (or null), so we
     * never steal a user-side activate the user took after Undo.
     */
    private void replayResumeRoute(GraphOperation operation) {
        UUID resumedRouteId = requireUuid(operation.afterRefs(), "routeId");
        UUID sourceRouteId = requireUuid(operation.afterRefs(), "sourceRouteId");
        UUID targetNodeId = requireUuid(operation.afterRefs(), "targetNodeId");
        UUID previousActiveRouteId = optionalUuid(operation.beforeRefs(), "previousActiveRouteId");
        UUID currentActive = projectRepository.findById(operation.projectId())
                .map(com.specagent.project.Project::activeRouteId)
                .orElse(null);
        if (!java.util.Objects.equals(currentActive, previousActiveRouteId)) {
            throw new IllegalStateException(
                    "撤销后产生了新的活动路线选择，无法恢复该 RESUME 路线");
        }
        Route resumed = routeRepository.findById(resumedRouteId)
                .orElseThrow(() -> new IllegalStateException("Resume route missing during redo: " + resumedRouteId));
        if (resumed.lifecycleStatus() != RouteLifecycleStatus.DELETED) {
            throw new IllegalStateException("RESUME 路线状态已变化，无法恢复");
        }
        Node target = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalStateException("Target Question missing during redo: " + targetNodeId));
        if (target.isRetracted()) {
            throw new IllegalStateException("Target Question must not be retracted: " + targetNodeId);
        }
        // Source route must still be present (it is read-only here).
        routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalStateException("Source route missing during redo: " + sourceRouteId));
        // Inherited prefix provenance: refs must still be there (we never
        // deleted them in compensate, but defend against external mutations).
        if (routeInheritedAnswerRepository.findByBranchRouteId(resumedRouteId).isEmpty()) {
            // Empty prefix is also a legal state; nothing to restore.
        }
        // No local answer on the resume route (compensate already enforced it,
        // but defend).
        List<UUID> lineage = routeHistoryResolver.resolveLineage(targetNodeId);
        boolean alreadyAnswered = routeHistoryResolver
                .resolveEffectiveAnswers(resumedRouteId, lineage).stream()
                .anyMatch(answer -> answer.nodeId().equals(targetNodeId));
        if (alreadyAnswered) {
            throw new IllegalStateException("RESUME 路线上该问题已产生回答，无法 Redo RESUME 创建");
        }

        routeService.restoreRoute(operation.projectId(), resumedRouteId);
        routeService.setActiveRoute(operation.projectId(), resumedRouteId);
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

    private void replayRelation(GraphOperation operation) {
        UUID relationId = requireUuid(operation.afterRefs(), "relationId");
        NodeRelation relation = relationRepository.findById(relationId)
                .orElseThrow(() -> new IllegalStateException("Relation missing during redo: " + relationId));
        if (relation.isActive()) {
            throw new IllegalStateException("关系已恢复，无法重复恢复");
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
        if (nodeRepository.existsByParentNodeId(node.id())) {
            throw new IllegalStateException("节点已有后续内容，请先处理其下游节点");
        }
        if (answerRepository.existsByNodeId(node.id())) {
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
            case CREATE_BRANCH_AND_APPEND -> "已撤销：新建分支";
            case RESUME_QUESTION_FROM_HISTORY -> "已撤销：恢复历史未答问题";
            case CREATE_SEMANTIC_RELATION -> "已撤销：添加语义关系";
            case SET_KNOWLEDGE_STATUS -> "已撤销：知识状态变更";
            case ACCEPT_AGENT_PROPOSAL -> "已撤销：接受提案";
        };
    }

    private String describeRedo(GraphOperation operation) {
        return switch (operation.type()) {
            case CREATE_DRAFT_NODE -> "已恢复：创建草稿节点";
            case EDIT_DRAFT_NODE -> "已恢复：编辑草稿节点";
            case APPEND_CONTINUATION -> "已恢复：继续探索";
            case ATTACH_RESOURCE -> "已恢复：添加资源";
            case CREATE_BRANCH_AND_APPEND -> "已恢复：新建分支";
            case RESUME_QUESTION_FROM_HISTORY -> "已恢复：恢复历史未答问题";
            case CREATE_SEMANTIC_RELATION -> "已恢复：添加语义关系";
            case SET_KNOWLEDGE_STATUS -> "已恢复：知识状态变更";
            // Unreachable: an ACCEPT_AGENT_PROPOSAL can never be in UNDONE
            // state because undo rejects it; kept for switch exhaustiveness.
            case ACCEPT_AGENT_PROPOSAL -> "已恢复：接受提案";
        };
    }
}
