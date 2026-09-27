package com.specagent.workspace.graph;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:UndoRedoIntegrationTest.java
 *
 * 测试目标:撤销/重做作为按操作类型的补偿——重放前先检查前置条件,
 * 不可变历史绝不被删除,新工作会截断 redo 分支。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UndoRedoIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private com.specagent.agent.policy.AgentProposalService proposalService;
    @Autowired private com.specagent.agent.runtime.ProposalAcceptanceService acceptanceService;
    @Autowired private com.specagent.workspace.route.RouteService routeService;
    @Autowired private com.specagent.workspace.project.ProjectRepository projectRepository;

    private Project project;
    private Route route;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("撤销重做测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    @Test
    void undoRootDraftClearsRouteAnchorAndRedoRestoresIt() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "根草稿"));

        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        UndoRedoService.UndoRedoResult undo = undoRedoService.undo(project.id());
        assertThat(undo.description()).contains("撤销");

        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();

        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        Route restored = routeRepository.findById(route.id()).orElseThrow();
        assertThat(restored.tipNodeId()).isEqualTo(root.id());
        assertThat(restored.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
    }

    @Test
    void undoAppendRollsBackTipToParent() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        undoRedoService.undo(project.id());

        Route rolled = routeRepository.findById(route.id()).orElseThrow();
        assertThat(rolled.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
    }

    @Test
    void floatingDraftNeverTouchesRouteAnchorAndUndoRedoKeepsItDisconnected() {
        // 路线上已有内容;游离灵感不得改写它。
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node floating = commandService.createFloatingDraftNode(
                project.id(), route.id(), "IDEA", Map.of("text", "灵感"));

        Route afterCreate = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterCreate.tipNodeId()).isEqualTo(root.id());
        assertThat(floating.parentNodeId()).isNull();
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();

        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        Route afterUndo = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterUndo.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isTrue();

        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        Route afterRedo = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterRedo.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();
    }

    /**
     * 已阻塞的回归:完全不带路线创建的游离草稿(项目活跃路线被归档/移除,
     * routeId=null)必须在不查询任何路线的情况下完成 undo 和 redo——此前
     * replayNodeCreation 在 isFloatingCreation 分支之前就解析路线,导致 redo
     * 在路线缺失的查询处失败。
     */
    @Test
    void floatingNoRouteCreationUndoRedoWorksWithoutAnyRoute() {
        // 1. 项目先有活跃路线,然后将其归档,使项目完全没有活跃路线。
        UUID firstActiveRouteId = project.activeRouteId();
        routeService.archiveRoute(project.id(), firstActiveRouteId);
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        // 2. 以 routeId=null 创建游离节点(创建上下文也没有路线)。
        Node floating = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "无路线灵感"));

        // 4. 节点完全游离,项目仍没有活跃路线。
        assertThat(floating.parentNodeId()).isNull();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        Route archived = routeRepository.findById(firstActiveRouteId).orElseThrow();
        UUID archivedTip = archived.tipNodeId();
        UUID archivedRoot = archived.rootNodeId();

        // 5. Undo:游离节点被撤回;active 保持 null;已归档路线未被触碰。
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isTrue();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().tipNodeId())
                .isEqualTo(archivedTip);
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().rootNodeId())
                .isEqualTo(archivedRoot);

        // 6-10. Redo:节点被恢复;active 保持 null;已归档路线仍未被触碰。
        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().tipNodeId())
                .isEqualTo(archivedTip);
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().rootNodeId())
                .isEqualTo(archivedRoot);
    }

    /**
     * 修复"第二次 undo 永久失败"的问题:自身已被 undo(软撤回)的 child
     * 不得阻碍撤销其 parent。此前的存在性检查忽略了 {@code retracted_at},
     * 一旦存在任何后代,parent 的创建就永远无法被撤销。
     */
    @Test
    void undoRootSucceedsAfterChildUndoBecauseRetractedChildIsNotLiveDownstream() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        // 撤销 child(最新操作)。它是软撤回,并非删除。
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();

        // 已撤回的 child 不再算作存活的下游历史,因此 parent 的创建现在可以撤销。
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();
        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();
    }

    /**
     * B2.2 端到端:创建 root;追加 child;undo child;undo root;redo root;
     * redo child——每一步都成功,路线 root/tip 从(已撤回的)child 的 undo
     * 中被正确恢复。
     */
    @Test
    void undoChildThenRootThenRedoRootThenChildRestoresRoute() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        undoRedoService.undo(project.id()); // 撤销 child(叶子)
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        undoRedoService.undo(project.id()); // 撤销 root——不得被阻碍
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();

        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();

        // Redo 先重放最近被撤销的(root),再重放 child。
        undoRedoService.redo(project.id());
        Route afterRoot = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterRoot.tipNodeId()).isEqualTo(root.id());
        assertThat(afterRoot.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();

        undoRedoService.redo(project.id());
        Route afterChild = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterChild.tipNodeId()).isEqualTo(child.id());
        assertThat(afterChild.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isFalse();
    }

    @Test
    void redoIsCutOffByNewWork() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        undoRedoService.undo(project.id());

        // undo 之后的新工作截断了 redo 分支。
        commandService.createRootDraftNode(
                project.id(), route.id(), "IDEA", Map.of("text", "新内容"));

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法恢复");
    }

    @Test
    void undoDraftEditRestoresPriorContent() {
        Node draft = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "初稿"));
        commandService.reviseDraftNode(
                project.id(), draft.id(), "REQUIREMENT", Map.of("text", "第二稿"));

        undoRedoService.undo(project.id());

        Node restored = nodeRepository.findById(draft.id()).orElseThrow();
        assertThat(restored.contentText()).isEqualTo("初稿");
        assertThat(restored.subtype()).isEqualTo("NOTE");

        undoRedoService.redo(project.id());
        Node redone = nodeRepository.findById(draft.id()).orElseThrow();
        assertThat(redone.contentText()).isEqualTo("第二稿");
        assertThat(redone.subtype()).isEqualTo("REQUIREMENT");
    }

    @Test
    void undoBranchCreationSoftDeletesRouteAndRedoRestoresIt() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node tip = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "tip")).node();

        GraphCommandService.ContinuationResult branch = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "IDEA", Map.of("text", "branch"));
        assertThat(branch.branched()).isTrue();

        undoRedoService.undo(project.id()); // 撤销分支创建

        Route deleted = routeRepository.findById(branch.route().id()).orElseThrow();
        assertThat(deleted.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.DELETED);
        assertThat(nodeRepository.findById(branch.node().id()).orElseThrow().isRetracted()).isTrue();
        // 原路线未被触碰。
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tip.id());

        undoRedoService.redo(project.id());
        Route restored = routeRepository.findById(branch.route().id()).orElseThrow();
        assertThat(restored.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(restored.tipNodeId()).isEqualTo(branch.node().id());
    }

    @Test
    void undoRelationRetractsAndRedoReactivates() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();

        undoRedoService.redo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * 严格屏障契约:被接受的 agent 提案会记录为不可回滚的 ACTIVE 操作,并
     * 充当 undo 历史的屏障。更早的可回滚工作从此对 undo 不可达——服务绝不
     * 跳过屏障去补偿更旧的操作,因为被接受提案的效果可能依赖那些更旧的状态。
     */
    @Test
    void acceptedAgentProposalIsAnUndoHistoryBarrier() {
        // 先做可回滚的用户工作。
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        assertThat(undoRedoService.canUndo(project.id())).isTrue();

        // 再通过真实的提案接受执行不可回滚的 agent 变更。与所有运行时生成
        // 的创建节点提案一样,该提案以路线 tip(root)为锚点;没有锚点的
        // 创建节点提案只在仍然为空的路线才有效。
        com.specagent.agent.protocol.ActionProposal proposal =
                new com.specagent.agent.protocol.ActionProposal(
                        "CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                "content", Map.of("text", "agent 结论")),
                        java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
                        List.of(), java.util.UUID.randomUUID(), "idem-" + java.util.UUID.randomUUID(),
                        List.of("node:" + root.id()));
        var pending = proposalService.createProposal(proposal,
                java.util.UUID.randomUUID(), project.id(), route.id());
        acceptanceService.acceptAndExecute(pending.id(), "user");

        // ACCEPT_AGENT_PROPOSAL 条目为 ACTIVE 且不可回滚。
        var acceptOperation = commandService.listOperations(project.id()).stream()
                .filter(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL)
                .findFirst().orElseThrow();
        assertThat(acceptOperation.reversible()).isFalse();
        assertThat(acceptOperation.status()).isEqualTo(GraphOperation.Status.ACTIVE);

        // 屏障语义:更早的可回滚创建不再作为可撤销项提供,直接尝试 undo
        // 也会快速失败被拒。
        assertThat(undoRedoService.canUndo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.undo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可撤销");
        // 被拒绝的尝试没有撤销任何东西。
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
        assertThat(undoRedoService.canRedo(project.id())).isFalse();

        // 屏障之上的新可回滚工作又可以撤销了——但只能撤到屏障处,绝不跨越。
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        UndoRedoService.UndoRedoResult undo = undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        // 这次 undo 之后,屏障再次阻止更早的撤销。
        assertThat(undoRedoService.canUndo(project.id())).isFalse();
    }

    /**
     * B2.1:redo 优先重放最近被撤销的操作。多次 undo 再多次 redo 必须按
     * {@code undoneAt}(而非 {@code createdAt})重建原始操作顺序:最后被撤销
     * 的最先被重做,一系列编辑按其编写顺序被恢复。
     */
    @Test
    void redoReplaysMostRecentlyUndoneOperationFirst() {
        Node draft = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "初稿"));
        commandService.reviseDraftNode(project.id(), draft.id(), "REQUIREMENT", Map.of("text", "第二稿"));
        commandService.reviseDraftNode(project.id(), draft.id(), "RISK", Map.of("text", "第三稿"));

        // 撤销三次编辑(每次都撤销最新一条)。
        undoRedoService.undo(project.id());
        undoRedoService.undo(project.id());
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("初稿");

        // Redo 按撤销的逆序重放:初稿,然后第二稿,然后第三稿。
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("初稿");
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("第二稿");
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("第三稿");
    }

    /**
     * B2.4-1:关系的 undo 撤回它,redo 通过不变量边界重新激活它
     * (undoRelationRetractsAndRedoReactivates 也覆盖了此场景,这里为
     * B2.4 矩阵保留)。
     */
    @Test
    void redoRelationReactivatesThroughInvariantBoundary() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();

        undoRedoService.redo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * B2.4-2:撤销一条关系后,中间的新工作重新创建了相同的关系(现在为活跃)。
     * redo 必须被拒绝(被新工作截断,重放中的重复兜底也会触发)。原关系保持
     * 撤回状态。
     */
    @Test
    void redoRelationRejectedWhenConflictingActiveRelationExists() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());

        // 中间的新工作重新创建了完全相同的关系(现在为活跃)。
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
        assertThat(relationRepository.findActiveByProject(project.id()).get(0).relationType())
                .isEqualTo(NodeRelationType.SUPPORTS);
    }

    /**
     * B2.4-3:撤销 A DEPENDS_ON B 后,中间的新工作创建了 B DEPENDS_ON A。
     * 重放 A DEPENDS_ON B 会闭合成环,因此 redo 被拒绝,原关系保持撤回状态。
     */
    @Test
    void redoDependsOnRejectedWhenItWouldCreateCycle() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id()); // 撤销 A DEPENDS_ON B

        // 中间的新工作:B DEPENDS_ON A。此时重放 A DEPENDS_ON B 会形成环
        // (A -> B -> A)。
        commandService.createSemanticRelation(
                project.id(), b.id(), a.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null);

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class);
        // 只有 B -> A 是活跃的;A -> B 保持撤回。
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * B2.4-4:关系被撤销后,其端点被撤回。redo 必须通过不变量边界重新校验并
     * 被拒绝,因为关系不能引用已撤回的节点。
     */
    @Test
    void redoRelationRejectedWhenEndpointRetracted() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();

        // 端点 B 被带外撤回(例如由与本操作栈正交的另一个图操作)。
        nodeService.setRetracted(b.id(), true);

        // 截断机制未触发(没有新的 GraphOperation),但不变量边界拒绝了重放。
        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETRACTED_NODE_REFERENCE");
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();
    }
}
