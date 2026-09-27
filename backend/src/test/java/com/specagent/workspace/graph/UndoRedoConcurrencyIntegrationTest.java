package com.specagent.workspace.graph;

import com.specagent.agent.action.StaleProposalException;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runtime.ProposalAcceptanceService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:UndoRedoConcurrencyIntegrationTest.java
 *
 * 测试目标:撤销/重做的并发正确性——两个真实独立事务在同一项目的操作栈
 * 上竞争时,必须彼此串行化、也必须与进行中的图变更串行化,从而线性
 * undo/redo 栈永远不会出现半应用或重复应用。
 *
 * 刻意不使用 {@code @Transactional}:每个竞争者必须运行在自己的数据库
 * 事务中(服务方法本身有事务并获取相关行锁),因此准备数据需要先提交,
 * 行锁才能真正产生竞争。
 */
@SpringBootTest
@ActiveProfiles("test")
class UndoRedoConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private AnswerService answerService;
    @Autowired private NodeService nodeService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteService routeService;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private GraphOperationRepository operationRepository;
    @Autowired private AgentProposalService proposalService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;

    @AfterEach
    void cleanUp() {
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM agent_run_events "
                + "WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks "
                + "WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers "
                + "WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * B2.3:Answer 落库与 Undo 在同一节点上竞争。节点行锁将二者串行化,恰好
     * 一方获胜——"已撤回节点却携带不可变 Answer"这一禁止状态永远不可被观察到。
     * Answer 获胜则 Undo 快速失败(节点保持活跃);Undo 获胜则随后的 Answer
     * 快速失败(RETRACTED_NODE_REFERENCE)。
     */
    @Test
    void undoAndAnswerFinalizationNeverCoexistRetractedNodeWithImmutableAnswer() throws Exception {
        project = projectService.createProject("撤销与回答并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> answerFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), routeId, root.id(), null, "回答A", "user"));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt answerAttempt = answerFuture.get(60, TimeUnit.SECONDS);

            // 两个冲突操作中恰好一个成功。
            assertThat(undoAttempt.success ^ answerAttempt.success)
                    .as("exactly one of undo / finalize may succeed")
                    .isTrue();

            boolean nodeRetracted = nodeRepository.findById(root.id()).orElseThrow().isRetracted();
            boolean answerExists = answerService.existsAnswerFor(routeId, root.id());

            // 禁止的共存状态永远不可被观察到。
            assertThat(nodeRetracted && answerExists)
                    .as("a retracted node must never carry an immutable answer")
                    .isFalse();

            if (undoAttempt.success) {
                assertThat(nodeRetracted).isTrue();
                assertThat(answerExists).isFalse();
                assertThat(answerAttempt.error).isInstanceOf(IllegalStateException.class);
            } else {
                assertThat(answerAttempt.success).isTrue();
                assertThat(nodeRetracted).isFalse();
                assertThat(answerExists).isTrue();
                assertThat(undoAttempt.error).isInstanceOf(IllegalStateException.class);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B2.5:两个并发 undo 作用于同一个可撤销操作时,不得对同一操作作用两次。
     * 项目行锁将二者串行化:第一个事务撤销该操作(UNDONE),第二个事务在锁内
     * 重读操作栈,发现已无可撤销内容。恰好发生一次状态迁移。
     */
    @Test
    void concurrentUndoOfSingleOperationDoesNotDoubleApply() throws Exception {
        project = projectService.createProject("并发撤销 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        commandService.createRootDraftNode(project.id(), routeId, "NOTE", Map.of("text", "root"));
        // 存在恰好一个可回滚的 ACTIVE 操作。

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> a = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> b = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });

            Attempt attemptA = a.get(60, TimeUnit.SECONDS);
            Attempt attemptB = b.get(60, TimeUnit.SECONDS);

            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one concurrent undo may succeed")
                    .isTrue();
            long undoneCount = operationRepository.findByProject(project.id()).stream()
                    .filter(op -> op.status() == GraphOperation.Status.UNDONE)
                    .count();
            assertThat(undoneCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B2.5:Undo 与进行中的图变更(创建语义关系)竞争时,绝不能产生半应用的
     * 操作栈。项目行锁串行化两个事务,各自都观察到一致的操作日志:要么
     * child 被撤回、关系创建拒绝已撤回端点;要么关系创建在 child 存活时成功、
     * 之后被撤销——但 child 绝不会同时处于"已撤回"且"被活跃关系引用"的状态。
     */
    @Test
    void undoAndConcurrentMutationAreSerializedWithoutHalfAppliedStack() throws Exception {
        project = projectService.createProject("撤销与变更并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), routeId, root.id(), "NOTE", Map.of("text", "child")).node();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> relFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), root.id(), child.id(), NodeRelationType.SUPPORTS,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt relAttempt = relFuture.get(60, TimeUnit.SECONDS);

            // Undo 总是针对有效的 ACTIVE 操作,因此必然成功。
            assertThat(undoAttempt.success).isTrue();

            boolean childRetracted = nodeRepository.findById(child.id()).orElseThrow().isRetracted();
            boolean relationActive = !relationRepository.findActiveByProject(project.id()).isEmpty();

            // 不存在半应用的中间状态:已撤回的 child 绝不会是活跃关系的端点。
            assertThat(childRetracted && relationActive)
                    .as("retracted child must not be referenced by an active relation")
                    .isFalse();

            // 关系创建(如果执行了)要么成功(child 存活),要么因端点已撤回
            // 而快速失败——绝不出现部分状态。
            if (relationActive) {
                assertThat(relAttempt.success).isTrue();
                assertThat(childRetracted).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 3 项——Undo 与 appendContinuation 竞争。两个写方都先获取同一
     * 项目行锁,因此最终物化的图与 GraphOperation 栈始终是线性的:ACTIVE 的
     * 创建操作不可能指向已撤回的节点,UNDONE 的创建操作也不可能指向存活节点。
     */
    @Test
    void undoAndAppendContinuationLeaveLinearStackAndGraph() throws Exception {
        project = projectService.createProject("撤销与续写并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), routeId, root.id(), "NOTE", Map.of("text", "child")).node();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> appendFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.appendContinuation(
                        project.id(), routeId, child.id(), "NOTE", Map.of("text", "new")));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt appendAttempt = appendFuture.get(60, TimeUnit.SECONDS);

            // undo 总是成功(总有它可以针对的有效 ACTIVE 操作);续写要么在
            // undo 之前成功(随后被 undo 回滚),要么在其之后失败(child 已
            // 不在 tip 谱系上)。绝不出现半应用的中间状态。
            assertThat(undoAttempt.success).isTrue();
            assertLinearOperationStack(project.id());
            assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 3 项——Undo 与 reviseDraftNode 竞争。项目行锁串行化两个事务:
     * 要么编辑先落库、undo 恢复先前内容;要么 undo 先落库、编辑应用在恢复后的
     * 状态上。最终节点内容必须是两种串行顺序之一,操作日志必须保持一致的
     * 线性栈。
     */
    @Test
    void undoAndReviseDraftNodeNeverLoseOrDoubleApplyARevision() throws Exception {
        project = projectService.createProject("撤销与编辑并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "v0"));
        commandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "v1"));

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> reviseFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.reviseDraftNode(
                        project.id(), draft.id(), "NOTE", Map.of("text", "v2")));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt reviseAttempt = reviseFuture.get(60, TimeUnit.SECONDS);

            assertThat(undoAttempt.success).isTrue();
            assertLinearOperationStack(project.id());

            // 草稿本身绝不能被这两个操作撤回。
            assertThat(nodeRepository.findById(draft.id()).orElseThrow().isRetracted()).isFalse();
            // 最终内容是两种串行结果之一:v1(undo 在编辑后执行)或 v2(编辑在
            // undo 后执行)。出现 "v0" 意味着编辑被静默丢失。
            String finalText = (String) nodeRepository.findById(draft.id())
                    .orElseThrow().content().get("text");
            assertThat(finalText).isIn("v1", "v2");
            // 最后一条 ACTIVE 的 EDIT 操作必须描述当前生效的内容。
            var activeEdit = operationRepository.findByProject(project.id()).stream()
                    .filter(op -> op.type() == GraphOperation.Type.EDIT_DRAFT_NODE)
                    .filter(op -> op.status() == GraphOperation.Status.ACTIVE)
                    .reduce((first, second) -> second);
            if (activeEdit.isPresent()) {
                assertThat(activeEdit.get().afterRefs().get("content"))
                        .isEqualTo(Map.of("text", finalText));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 3 项——Undo 与已被接受的 CREATE_NODE 提案竞争。接受操作现在
     * 和所有其他用户可见的图写方一样先获取项目锁(项目 → 提案 → 图),因此
     * 过期的接受绝不能复活已撤回的锚点:要么接受先落库、随后的 undo 命中
     * 不可回滚的 ACCEPT 屏障;要么 undo 先落库、接受因过期而失败。
     * 提案永远不会越过既定边界。
     */
    @Test
    void undoAndAcceptedCreateNodeNeverResurrectRetractedAnchor() throws Exception {
        project = projectService.createProject("撤销与接受提案并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));

        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "agent 结论")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                // 创建节点的提案以路线 tip 作为锚点;接受操作的过期重校验和
                // 事务边界都依据当前 tip 重新核验该锚点。
                List.of("node:" + root.id()));
        AgentProposal pending = proposalService.createProposal(
                proposal, UUID.randomUUID(), project.id(), routeId);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> acceptFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> acceptanceService.acceptAndExecute(pending.id(), "user"));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt acceptAttempt = acceptFuture.get(60, TimeUnit.SECONDS);

            assertLinearOperationStack(project.id());
            // 提案恰好被裁决一次,绝不复活。
            ProposalStatus finalStatus = proposalService.getProposal(pending.id())
                    .orElseThrow().status();

            if (acceptAttempt.success) {
                // 接受赢得了锁:生成的节点存活,随后的 undo 命中不可回滚的
                // ACCEPT 屏障。
                assertThat(finalStatus).isEqualTo(ProposalStatus.ACCEPTED);
                assertThat(undoAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("不可撤销");
                assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
                assertThat(operationRepository.findByProject(project.id()).stream()
                        .anyMatch(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL
                                && op.status() == GraphOperation.Status.ACTIVE)).isTrue();
            } else {
                // undo 赢得了锁:锚点在接受重校验之前已被撤回,接受因过期失败。
                assertThat(finalStatus).isEqualTo(ProposalStatus.PROPOSED);
                assertThat(acceptAttempt.error).isInstanceOf(StaleProposalException.class);
                assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();
                assertThat(operationRepository.findByProject(project.id()).stream()
                        .noneMatch(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL)).isTrue();
            }
            // 锚点所在路线的 tip 绝不是已撤回的节点。
            Route route = routeRepository.findById(routeId).orElseThrow();
            if (route.tipNodeId() != null) {
                assertThat(nodeRepository.findById(route.tipNodeId())
                        .orElseThrow().isRetracted()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 任意竞争之后的线性栈不变量:ACTIVE 的节点创建操作必须引用存活
     * (未撤回)的节点,UNDONE 的创建操作必须引用已撤回的节点。任何违反都
     * 意味着出现了半应用的 undo/变更交错。
     */
    private void assertLinearOperationStack(UUID projectId) {
        for (GraphOperation op : operationRepository.findByProject(projectId)) {
            boolean creationOp = switch (op.type()) {
                case CREATE_DRAFT_NODE, APPEND_CONTINUATION, ATTACH_RESOURCE,
                     CREATE_BRANCH_AND_APPEND -> true;
                default -> false;
            };
            if (!creationOp || !(op.afterRefs().get("nodeId") instanceof String nodeId)) {
                continue;
            }
            Node node = nodeRepository.findById(UUID.fromString(nodeId)).orElse(null);
            if (node == null) {
                continue;
            }
            boolean active = op.status() == GraphOperation.Status.ACTIVE;
            assertThat(node.isRetracted())
                    .as("linear stack: op %s on node %s status %s", op.type(), nodeId, op.status())
                    .isEqualTo(!active);
        }
    }

    /** 单个竞争者的结果:成功,或失败时抛出的具体异常。 */
    private record Attempt(boolean success, Throwable error) {
        static Attempt run(Callable<?> action) {
            try {
                action.call();
                return new Attempt(true, null);
            } catch (Throwable t) {
                return new Attempt(false, t);
            }
        }
    }
}
