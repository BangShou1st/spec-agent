package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.UndoRedoService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentAutoExecuteMutationIntegrationTest.java
 *
 * 测试目标:用真实数据库验证自动执行图变更边界({@link AgentGraphMutationService})的
 * 并发正确性。自动执行路径(通过 {@link ProposalActionExecutor} 的 REQUEST_USER_INPUT /
 * CREATE_NODE)必须在项目行锁下与所有项目级图写入方串行化、依据当前路线 tip 重新校验
 * 决策锚点、并以原子事务提交节点插入与 tip 更新。全程不用 mock:每个测试都在真实
 * PostgreSQL 行锁上竞争真实的服务层事务。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentAutoExecuteMutationIntegrationTest {

    private static final String AUTO_QUESTION = "自动执行追加的节点问题?";

    @Autowired private ProposalActionExecutor executor;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteService routeService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private Project project;
    private Route route;
    private Node tip;
    private int baselineChildren;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("自动执行边界测试-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        tip = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "REQUIREMENT", Map.of("text", "根节点"));
        baselineChildren = childrenOf(tip.id());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                + "(SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN "
                + "(SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        // 必须先删 routes 再删 nodes:分支/续写路线通过 branch_at_node_id /
        // root / tip 外键引用节点。
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    /** 单个竞争者的结果:成功时返回产生的节点 id,失败时携带原始异常。 */
    private record Attempt(boolean success, UUID producedNodeId, Throwable error) {
        static Attempt run(Callable<UUID> action) {
            try {
                return new Attempt(true, action.call(), null);
            } catch (Throwable t) {
                return new Attempt(false, null, t);
            }
        }
    }

    /**
     * 用共享屏障让两个竞争者同时到达服务层;先后顺序由数据库行锁决定,
     * 而不是由测试决定。
     */
    private Attempt[] race(Callable<UUID> first, Callable<UUID> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startLine = new CyclicBarrier(2);
        try {
            Future<Attempt> f1 = pool.submit(() -> Attempt.run(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return first.call();
            }));
            Future<Attempt> f2 = pool.submit(() -> Attempt.run(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return second.call();
            }));
            return new Attempt[]{f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 生产环境的自动执行入口:一个锚定在 {@code anchorNodeId} 的 agent
     * REQUEST_USER_INPUT 提案,经真实的 executor 执行进入事务边界。
     */
    private UUID autoAppend(UUID anchorNodeId) {
        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", AUTO_QUESTION,
                        "options", List.of(Map.of("label", "选项")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + anchorNodeId));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(),
                UUID.randomUUID(), anchorNodeId, null, null);
        ActionResult result = executor.execute(proposal, context);
        return result.producedNodeId();
    }

    private int childrenOf(UUID nodeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE parent_node_id = ?",
                Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    private long agentAutoNodes() {
        return nodeService.listProject(project.id()).stream()
                .filter(node -> AUTO_QUESTION.equals(node.question()))
                .count();
    }

    // ------------------------------------------------------------------
    // A. 自动执行 REQUEST_USER_INPUT 与并发 undo 的竞争
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentUndoNeverLeavesDanglingState() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    undoRedoService.undo(project.id());
                    return null;
                });
        Attempt auto = attempts[0];
        Node rootNow = nodeService.getNode(tip.id()).orElseThrow();
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // agent 追加先提交:它自己的 CREATE_DRAFT_NODE(AGENT)现在位于操作日志
            // 顶部,undo 补偿的是 AGENT 节点(撤回 + tip 回滚)——"存活子节点拒绝
            // undo"的旧前提早于 agent 变更进入日志。根草稿保持完好,不会出现
            // 半提交状态。
            assertThat(attempts[1].error()).as("undo compensates the latest (agent) operation")
                    .isNull();
            assertThat(rootNow.isRetracted()).isFalse();
            Node agentNode = nodeService.getNode(auto.producedNodeId()).orElseThrow();
            assertThat(agentNode.isRetracted()).isTrue();
            assertThat(routeNow.tipNodeId()).isEqualTo(tip.id());
        } else {
            // undo 先提交(根节点被撤回,路线被清空):agent 追加必须以过期为由
            // fail-closed,什么也不插入。
            assertThat(auto.error()).isInstanceOf(StaleProposalException.class);
            assertThat(rootNow.isRetracted()).isTrue();
            assertThat(routeNow.tipNodeId()).isNull();
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
            assertThat(agentAutoNodes()).isZero();
        }
        // 不允许悬空的半状态:被 undo 的根节点绝不能把追加的 agent 子节点留在路线 tip 上。
        if (rootNow.isRetracted()) {
            assertThat(routeNow.tipNodeId()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // B. 自动执行追加与并发续写(continuation)的竞争
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentContinuationHasSingleSerializedOrdering() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    graphCommandService.appendContinuation(
                            project.id(), route.id(), tip.id(), "NOTE",
                            Map.of("text", "用户续写"));
                    return null;
                });
        Attempt auto = attempts[0];
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // 自动执行抢到了锚点位:它的节点是原路线的 tip。续写绝不能在
            // 同一条路线上用自己的节点覆盖这个 tip。
            assertThat(routeNow.tipNodeId()).isEqualTo(auto.producedNodeId());
        } else {
            // 续写先抢到锚点位;自动执行必须以过期为由 fail-closed,
            // 绝不能对着已移动的 tip 插入节点。
            assertThat(auto.error()).isInstanceOf(StaleProposalException.class);
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
            assertThat(agentAutoNodes()).isZero();
        }
    }

    @Test
    void staleAutoExecuteAfterContinuationNeverOverwritesTheNewerTip() {
        Node cont = graphCommandService.appendContinuation(
                        project.id(), route.id(), tip.id(), "NOTE", Map.of("text", "用户先续写"))
                .node();

        // agent 决策锚定在旧 tip 上,而旧 tip 已不再是 tip:追加必须 fail-closed,
        // 只保留较新的节点。
        assertThatThrownBy(() -> autoAppend(tip.id()))
                .isInstanceOf(StaleProposalException.class);
        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(cont.id());
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // C. 自动执行追加与归档源路线的竞争
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentArchiveNeverWritesToArchivedRoute() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    routeService.archiveRoute(project.id(), route.id());
                    return null;
                });
        Attempt auto = attempts[0];
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // 追加先完成;路线随后带着新 tip 归档。
            assertThat(routeNow.lifecycleStatus().code()).isEqualTo("archived");
            assertThat(routeNow.tipNodeId()).isEqualTo(auto.producedNodeId());
        } else {
            // 归档先完成:已归档的路线绝不能再接收迟到的过期 agent 节点。
            assertThat(auto.error())
                    .as("auto-execute against an archived route must fail closed")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not open");
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
            assertThat(routeNow.tipNodeId()).isEqualTo(tip.id());
            assertThat(agentAutoNodes()).isZero();
        }
    }

    @Test
    void archivedRouteNeverReceivesALaterStaleNode() {
        routeService.archiveRoute(project.id(), route.id());

        assertThatThrownBy(() -> autoAppend(tip.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tip.id());
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // D. 节点 INSERT 之后、路线 tip 更新之前失败时的回滚
    // ------------------------------------------------------------------

    @Test
    void failureAfterNodeInsertRollsBackTheNodeToo() {
        UUID tipBefore = routeRepository.findById(route.id()).orElseThrow().tipNodeId();

        // 变更边界把整个追加(节点 INSERT + tip/root 更新)放在同一个事务里。
        // 在节点插入之后注入失败——这里在事务提交前强制回滚——必须把已插入的
        // 节点连同 tip 更新一起回滚:绝不允许出现"节点可见但 tip 未推进"的状态。
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            autoAppend(tip.id());
            throw new IllegalStateException("injected failure after node insert");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tipBefore);
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // E. 未回答 INTERACTION Question 的不变量(回答之前不允许有子节点)
    // ------------------------------------------------------------------

    private Project newQuestionProject(String title, Node[] questionOut) {
        Project p = projectService.createProject(title + "-" + UUID.randomUUID());
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();
        Node q1 = nodeService.createRootNode(p.id(), r.id(),
                "未回答的澄清问题 Q1?", "purpose",
                List.of(new NodeOption(UUID.randomUUID(), "选项A", null)), true);
        questionOut[0] = q1;
        return p;
    }

    @Test
    void unansweredInteractionQuestionRejectsAgentChildAndKeepsTip() {
        // 真实集成流程:创建项目/路线,把 INTERACTION Question Q1 建为路线根节点,
        // 不为 Q1 定稿 Answer,然后用真实的 ProposalActionExecutor 尝试一个
        // 锚定在 Q1 上的 REQUEST_USER_INPUT。
        Node[] q1Box = new Node[1];
        Project p = newQuestionProject("未回答问题拒绝追加", q1Box);
        Node q1 = q1Box[0];
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();
        int childrenBefore = childrenOf(q1.id());
        long autoBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ? AND parent_node_id = ?",
                Long.class, p.id(), q1.id());
        UUID tipBefore = r.tipNodeId();

        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", "跟进问题 Q2?",
                        "purpose", "follow-up",
                        "options", List.of(Map.of("label", "选项B")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + q1.id()));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), p.id(), r.id(), UUID.randomUUID(), q1.id(), null, null);

        assertThatThrownBy(() -> executor.execute(proposal, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNANSWERED_QUESTION_HAS_CHILD");

        Route routeNow = routeRepository.findById(r.id()).orElseThrow();
        assertThat(routeNow.tipNodeId()).isEqualTo(q1.id()).isEqualTo(tipBefore);
        assertThat(childrenOf(q1.id())).isEqualTo(childrenBefore);
        long autoAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ? AND parent_node_id = ?",
                Long.class, p.id(), q1.id());
        assertThat(autoAfter).isEqualTo(autoBefore);
        assertThat(agentAutoNodesIn(p.id())).isZero();

        // 清理隔离项目
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", p.id());
    }

    @Test
    void finalizedAnswerAllowsAgentQuestionProgression() {
        Node[] q1Box = new Node[1];
        Project p = newQuestionProject("已回答后允许追问", q1Box);
        Node q1 = q1Box[0];
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();

        var answer = answerService.finalizeAnswer(
                p.id(), r.id(), q1.id(), null, "已回答 Q1", "test-user");
        answerPatchService.save(p.id(), r.id(), q1.id(), answer.id(), List.of(), null);

        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", "跟进问题 Q2?",
                        "purpose", "follow-up",
                        "options", List.of(Map.of("label", "选项B")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + q1.id()));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), p.id(), r.id(), UUID.randomUUID(), q1.id(), null, null);

        ActionResult result = executor.execute(proposal, context);
        Node q2 = nodeService.getNode(result.producedNodeId()).orElseThrow();

        assertThat(q2.parentNodeId()).isEqualTo(q1.id());
        Route routeNow = routeRepository.findById(r.id()).orElseThrow();
        assertThat(routeNow.tipNodeId()).isEqualTo(q2.id());
        assertThat(childrenOf(q1.id())).isEqualTo(1);

        // 清理隔离项目
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", p.id());
    }

    private long agentAutoNodesIn(UUID projectId) {
        return nodeService.listProject(projectId).stream()
                .filter(node -> "跟进问题 Q2?".equals(node.question()))
                .count();
    }
}
