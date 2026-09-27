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
 * 文件名:UndoRedoService.java
 *
 * 用途:撤销/重做服务——基于类型化图操作日志的"操作特定补偿"。
 *
 * 撤销绝不物理删除不可变历史:节点创建通过软撤回加路线 tip 回滚来
 * 补偿;草稿编辑恢复先前内容;语义关系被标记为撤回;分支路线被软删除
 * 且可恢复。重做只在其前置条件仍成立时重放原逻辑操作;中间发生的、
 * 会产生冲突的新工作会让重做不可用,而不是强行重放。
 *
 * 线性栈语义:撤销针对最近的 ACTIVE 操作;重做针对最近的 UNDONE
 * 操作,前提是它被撤销后没有产生新的 ACTIVE 操作(新工作会切断重做
 * 分支,与常见编辑器的撤销历史完全一致)。
 *
 * 不可逆屏障:一条 ACTIVE 且不可逆的操作(已接受的 agent 提案)是
 * 撤销历史的屏障。撤销永不越过它——只要屏障还是最新的 ACTIVE 操作,
 * 更早的可逆操作都够不着,因为在已接受的 agent 工作之下补偿它们,
 * 可能悄悄破坏提案所依赖的图不变量。屏障处 {@link #canUndo} 返回 false,
 * UI 因此绝不会提供一个注定被拒的撤销。
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
     * 当最近的 ACTIVE 操作可逆时返回 true。栈顶的不可逆操作是撤销屏障:
     * 只要它还在,更老的 ACTIVE 操作都不可撤销——所以即使日志深处仍有
     * 可逆操作,这里也返回 false。
     */
    public boolean canUndo(UUID projectId) {
        return latestByStatus(projectId, GraphOperation.Status.ACTIVE)
                .map(op -> op.reversible())
                .orElse(false);
    }

    /** 最近一次被撤销的操作若仍可重放则返回 true。 */
    public boolean canRedo(UUID projectId) {
        return latestUndoneForRedo(projectId)
                .map(op -> redoNotCutOff(projectId, op))
                .orElse(false);
    }

    @Transactional
    public UndoRedoResult undo(UUID projectId) {
        // 让本项目内的栈变更串行:undo/redo 与实时的图变更(如
        // createSemanticRelation)都取同一把 project 行锁,因此两个并发
        // 撤销绝不会作用于同一操作,撤销也绝不会与一次新变更交错出
        // 半应用的栈。被撤回目标节点的专属锁稍后在补偿路径内、任何
        // 撤回决策之前再取。
        projectRepository.lockById(projectId);
        GraphOperation operation = latestByStatus(projectId, GraphOperation.Status.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("没有可撤销的操作"));
        if (!operation.reversible()) {
            throw new IllegalStateException("该操作不可撤销: " + operation.type());
        }
        compensate(operation);
        operationRepository.updateStatus(operation.id(), GraphOperation.Status.UNDONE,
                com.specagent.workspace.graph.GraphOperationRepository.nextTimestamp());
        // 重新读一次,让响应反映持久化后的 UNDONE 状态,
        // 而不是刚被补偿完的内存 ACTIVE 对象。
        return new UndoRedoResult(operationRepository.findById(operation.id()).orElse(operation),
                describeUndo(operation));
    }

    @Transactional
    public UndoRedoResult redo(UUID projectId) {
        projectRepository.lockById(projectId);
        // 重做针对最近被撤销的操作(undoneAt 最大):与常见编辑器一致,
        // 最后撤销的就是最先重做的。用 createdAt 排序在多次撤销下
        // 得不到正确顺序。
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
    // 按操作类型的撤销补偿
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
     * 撤销"接入悬浮节点":节点被重新摘下——它连同内容继续存在,
     * 只是失去路线归属。仅在其后没有追加任何内容时才合法,
     * lineage 历史绝不会被孤立。
     *
     * 接入有两种形态:节点成为 tip(空路线或纯知识头),或它挂在
     * 未变化的 INTERACTION tip 之下作为出处。记录的 {@code tipAdvanced}
     * 引用可区分两者;该引用出现之前的旧记录总是推进 tip,所以歧义由
     * 当前 tip 状态做 fail-closed 判定。
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
            // 仅出处式接入:tip 从未移动,只清空父指针即完整补偿。
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
     * 从第一次按 kind 区分的接入起,接入操作都会记录 {@code tipAdvanced};
     * 更早的记录总是推进 tip,所以引用缺失意味着"已推进",除非当前
     * tip 表现相反(对修复前写入的日志做 fail-closed 推断)。
     */
    private boolean tipAdvancedOf(GraphOperation operation, Route route, UUID nodeId) {
        Object recorded = operation.afterRefs().get("tipAdvanced");
        if (recorded instanceof Boolean b) {
            return b;
        }
        return route.tipNodeId() != null && route.tipNodeId().equals(nodeId);
    }

    /**
     * 撤销"摘线":节点按原样接回——作为路线 tip(tip 摘线),或重新挂回
     * lineage 之下(出处摘线,tip 不动)。旧记录一律是 tip 摘线。
     */
    private void compensateDisconnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.beforeRefs(), "parentId");
        boolean tipDetached = operation.afterRefs().get("tipDetached") instanceof Boolean b
                ? b
                : true; // 旧记录总是摘掉 tip
        Node node = requireActiveNode(operation.projectId(), nodeId);
        nodeRepository.lockById(nodeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing during undo: " + routeId));
        Instant now = Instant.now();
        if (!tipDetached) {
            // 出处摘线:tip 从未移动;先要求旧父节点仍在这条 lineage 上,
            // 再恢复父指针。
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
        // 悬浮创建(不经过任何路线)没有 routeId;
        // 悬浮草稿从未触碰路线的 tip/root。
        UUID routeId = optionalUuid(operation.afterRefs(), "routeId");
        Node node = requireActiveNode(operation.projectId(), nodeId);
        // 在决定撤回前先锁节点行,镜像 AnswerService.finalizeAnswer 的
        // 先锁后判模式:下面的可撤回性检查随后观察到的是权威、无竞态的
        // 节点状态,因此同一节点上正在进行的答案定稿或图变更不会在
        // 撤回提交后丢失(或输掉 lost-update 竞态)。
        nodeRepository.lockById(nodeId);
        requireRetractable(operation.projectId(), node, routeId);

        nodeService.setRetracted(nodeId, true);
        if (isFloatingCreation(operation)) {
            // 悬浮草稿从未触碰路线 tip/root;仅撤回即可完整补偿其创建。
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
        // 在决定撤回前先锁节点行(见 compensateNodeCreation)。
        nodeRepository.lockById(nodeId);
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Branch route missing during undo: " + routeId));
        if (route.tipNodeId() == null || !route.tipNodeId().equals(nodeId)) {
            throw new IllegalStateException("分支路线已继续推进，无法撤销创建的节点");
        }
        requireRetractable(operation.projectId(), node, routeId);

        // 先恢复活跃指针(旧记录没有显式的 previous-active 引用;分支的
        // 来源路线是安全回退),再经由裸流转核心做软删除——补偿绝不能
        // 自己再追加生命周期操作。
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
    // 路线操作的补偿
    // ------------------------------------------------------------------

    /**
     * 撤销 ROUTE_FORK / ROUTE_START:新路线被软删除——但仅当它仍停在
     * 创建时的位置(没有任何续写推进过它的 tip)。路线指向的节点是
     * 共享或悬浮的,永不被触碰。
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

    /** 重做 ROUTE_FORK / ROUTE_START:重新打开被软删除的路线。 */
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

    /** 撤销 ROUTE_REANSWER:撤回克隆的问题节点并软删除其路线。 */
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

    /** 重做 ROUTE_REANSWER:重新打开路线并解除克隆节点的撤回。 */
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

    /** 撤销 ROUTE_REGENERATE:撤回替换节点并重新打开来源路线。 */
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
            // SUPERSEDED → OPEN 是合法的逆向流转。
            routeService.transitionLifecycle(operation.projectId(), sourceRouteId,
                    RouteLifecycleStatus.OPEN);
        }
    }

    /** 重做 ROUTE_REGENERATE:重开替换路线、无需再撤回什么、重新取代来源。 */
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
            // OPEN → SUPERSEDED 仅保留给替换提交(状态机禁止把它作为用户
            // 生命周期命令),因此像实时提交路径那样直接重新应用取代。
            routeRepository.updateLifecycle(sourceRouteId, RouteLifecycleStatus.SUPERSEDED,
                    Instant.now());
        }
        routeService.transitionLifecycle(operation.projectId(), routeId,
                RouteLifecycleStatus.OPEN);
        nodeService.setRetracted(replacementNodeId, false);
        routeService.setActiveRoutePointer(operation.projectId(), routeId);
    }

    /**
     * 撤销 ROUTE_LIFECYCLE:应用逆向流转(严格遵循生命周期状态机——
     * 例如被恢复的 SUPERSEDED 路线无法"取消恢复"回 SUPERSEDED),
     * 并恢复记录的活跃路线指针。
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

    /** 重做 ROUTE_LIFECYCLE:重新应用正向流转。 */
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

    /** 把活跃路线指针恢复为记录值(相同时为 no-op)。 */
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
    // 按操作类型的重做重放(先检查前置条件)
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
     * 重做一次接入:把同一节点重新接为 tip(或对出处式接入,重新挂在
     * 未变化的 tip 之下),但仅当路线仍停在撤销后留下的位置——中间
     * 发生的新工作会 fail-closed,而不是悄悄重定 lineage 基准。
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

    /** 重做一次摘线:再次摘下,要求状态与撤销前一致。 */
    private void replayDisconnect(GraphOperation operation) {
        UUID nodeId = requireUuid(operation.afterRefs(), "nodeId");
        UUID routeId = requireUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.beforeRefs(), "parentId");
        boolean tipDetached = operation.afterRefs().get("tipDetached") instanceof Boolean b
                ? b
                : true; // 旧记录总是摘掉 tip
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
        // 悬浮创建没有 routeId;悬浮恢复绝不触碰路线 tip/root。
        UUID routeId = optionalUuid(operation.afterRefs(), "routeId");
        UUID parentId = optionalUuid(operation.afterRefs(), "parentId");
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing during redo: " + nodeId));
        if (!node.isRetracted()) {
            throw new IllegalStateException("节点已恢复，无法重复恢复");
        }

        if (isFloatingCreation(operation)) {
            // 悬浮草稿保持脱离:恢复它们不得触碰路线 tip/root、不得要求
            // 特定的 tip 状态,也绝不允许查询创建时从未引用过的路线。
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

    /** 记录的创建是否是独立(悬浮)草稿。 */
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
        // 撤销后路线(软删除)的 tip 停在创建的节点上;重做只有在该
        // tip 从未越过它时才可能。
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
     * 重新激活被撤销撤回的同一行关系,但必须先重跑与实时创建路径完全
     * 相同的不变量关卡({@code GraphCommandService.createSemanticRelation}
     * → {@link GraphInvariantValidator#validateRelationCreation})。
     * 重做绝不能绕过校验或铸造新的关系身份:如果中间发生的新工作
     * (一条冲突的激活关系、依赖成环、端点被撤回、跨项目或自引用)
     * 会让重放失效,则 fail-closed,而不是悄悄重定图的基准。
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
        // 与项目内并发的关变更串行,与实时创建路径完全一致。
        projectRepository.lockById(operation.projectId());
        // 端点存在 / 未撤回 / 同项目 / 非自引用,外加针对"当前图"的
        // DEPENDS_ON + DERIVED_FROM DAG 成环检查。
        invariantValidator.validateRelationCreation(operation.projectId(), sourceNodeId, targetNodeId, type);
        // 这条规范关系绝不能已有激活的重复行:若中间的新工作创建了一条,
        // 重放会违反唯一兜底,因此 fail-closed 而不是重定基准。规范对
        // 查找与实时去重一致(对称类型已规范化)。
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
    // 共享前置条件
    // ------------------------------------------------------------------

    /**
     * 创建的节点只有在它是叶子、没有不可变答案、也没有其他路线把它
     * 指为 tip 时才可撤回;否则下游历史会被悄悄孤立。
     */
    private void requireRetractable(UUID projectId, Node node, UUID owningRouteId) {
        // 只有存活(未撤回)的子孙才阻止撤回。一个自己已被撤销
        // (软撤回)的子节点不会阻止撤销其父节点——否则第二次撤销会被
        // 永久拒绝,线性栈永远无法越过它回退。
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
     * 重做目标:最近被撤销的操作——即 {@code undoneAt} 最大的 UNDONE 操作
     * (并列时按 id 决出确定性顺序)。只有操作转入 UNDONE 时才写入
     * {@code undoneAt},因此它记录了真实的撤销顺序;{@code createdAt}
     * 无法区分第三次撤销与更早的一次。没有 {@code undoneAt} 的 UNDONE
     * 操作(当前不会产生)会被忽略。
     */
    private java.util.Optional<GraphOperation> latestUndoneForRedo(UUID projectId) {
        return operationRepository.findByProject(projectId).stream()
                .filter(op -> op.status() == GraphOperation.Status.UNDONE && op.undoneAt() != null)
                .max(Comparator.comparing(GraphOperation::undoneAt)
                        .thenComparing(op -> op.id().toString()));
    }

    /**
     * 操作被撤销之后产生的任何新 ACTIVE 工作都会切断它的重做分支;
     * 用户必须显式重新发起该操作。
     */
    private boolean redoNotCutOff(UUID projectId, GraphOperation undone) {
        Instant undoneAt = undone.undoneAt() == null ? Instant.EPOCH : undone.undoneAt();
        return operationRepository.findByProject(projectId).stream()
                .noneMatch(op -> op.status() == GraphOperation.Status.ACTIVE
                        && op.createdAt().isAfter(undoneAt)
                        && !op.id().equals(undone.id()));
    }

    // ------------------------------------------------------------------
    // Ref 辅助方法
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
            // 不可达:ACCEPT_AGENT_PROPOSAL 永远不会处于 UNDONE 状态,
            // 因为撤销会直接拒绝它;保留此分支仅为 switch 穷尽性。
            case ACCEPT_AGENT_PROPOSAL -> "已恢复：接受提案";
        };
    }
}
