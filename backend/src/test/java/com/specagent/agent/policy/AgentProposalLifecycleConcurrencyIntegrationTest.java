package com.specagent.agent.policy;

import com.specagent.agent.runtime.ProposalAcceptanceService;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 文件名:AgentProposalLifecycleConcurrencyIntegrationTest.java
 *
 * 测试目标:用真实数据库验证提案终态生命周期的并发正确性——两个竞争事务通过真实
 * 服务层和真实 PostgreSQL 行锁竞争同一个 PROPOSED 行,不 mock、不人为串行化;
 * 终态迁移永远只允许一个赢家(接受/拒绝/过期两两竞争)。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentProposalLifecycleConcurrencyIntegrationTest {

    private static final String DECIDED_BY_A = "user-a";
    private static final String DECIDED_BY_B = "user-b";

    @Autowired private AgentProposalService proposalService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private Route route;
    private Node tip;
    private int baselineChildren;

    @BeforeEach
    void setUp() {
        project = projectService.createProject(
                "提案生命周期并发测试-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        tip = graphCommandService.appendContinuation(
                project.id(), route.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip 需求")).node();
        baselineChildren = childrenOf(tip.id());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    /** 单个竞争者的结果:调用正常返回,或携带失败时的原始异常。 */
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

    /**
     * 用共享屏障让两个竞争者同时到达服务层;赢家由数据库行锁决定,
     * 而不是由测试决定。
     */
    private Attempt[] race(Callable<?> first, Callable<?> second) throws Exception {
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

    private AgentProposal createPendingNodeProposal() {
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "离线同步可能产生冲突")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-conc-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        return proposalService.createProposal(proposal, UUID.randomUUID(),
                project.id(), route.id());
    }

    private int childrenOf(UUID nodeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE parent_node_id = ?",
                Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    private int acceptOperations(UUID proposalId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM graph_operations "
                        + "WHERE project_id = ? AND type = 'ACCEPT_AGENT_PROPOSAL' "
                        + "AND caused_by = ?",
                Integer.class, project.id(), "proposal:" + proposalId);
        return count == null ? 0 : count;
    }

    private AgentProposal reload(UUID proposalId) {
        return proposalService.getProposal(proposalId).orElseThrow();
    }

    private void assertSingleWinner(Attempt[] attempts) {
        long wins = (attempts[0].success() ? 1 : 0) + (attempts[1].success() ? 1 : 0);
        assertThat(wins).as("exactly one racer may win the terminal transition").isEqualTo(1);
        Attempt loser = attempts[0].success() ? attempts[1] : attempts[0];
        // 确定性的业务失败——绝不能是 500 风格的内部错误、SQL 约束冲突或静默的假成功。
        assertThat(loser.error()).isInstanceOf(ProposalAlreadyDecidedException.class);
        assertThat(loser.error())
                .hasMessageContaining("already been decided")
                .isNotInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void concurrentAcceptExecutesMutationExactlyOnce() throws Exception {
        AgentProposal pending = createPendingNodeProposal();

        Attempt[] attempts = race(
                () -> acceptanceService.acceptAndExecute(pending.id(), DECIDED_BY_A),
                () -> acceptanceService.acceptAndExecute(pending.id(), DECIDED_BY_B));
        assertSingleWinner(attempts);

        AgentProposal decided = reload(pending.id());
        assertThat(decided.status()).isEqualTo(ProposalStatus.ACCEPTED);

        // 图变更恰好发生一次,路线 tip 指向唯一产生的子节点——绝不能指向两个竞争节点。
        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
        UUID producedChildId = jdbcTemplate.queryForObject(
                "SELECT id FROM nodes WHERE parent_node_id = ?",
                UUID.class, tip.id());
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(producedChildId);

        // 一次接受,一条操作日志。
        assertThat(acceptOperations(pending.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by FROM agent_proposals WHERE id = ?",
                String.class, pending.id()))
                .as("decided_by comes from the winning caller")
                .isIn(DECIDED_BY_A, DECIDED_BY_B);
    }

    @Test
    void acceptVsRejectHasSingleWinner() throws Exception {
        AgentProposal pending = createPendingNodeProposal();

        Attempt[] attempts = race(
                () -> acceptanceService.acceptAndExecute(pending.id(), DECIDED_BY_A),
                () -> {
                    proposalService.rejectProposal(pending.id(), DECIDED_BY_B);
                    return null;
                });
        assertSingleWinner(attempts);

        AgentProposal decided = reload(pending.id());

        if (decided.status() == ProposalStatus.ACCEPTED) {
            // 合法结果 A:接受胜出——它的全部效果必须存在。
            assertThat(decided.decidedBy()).isEqualTo(DECIDED_BY_A);
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
            assertThat(acceptOperations(pending.id())).isEqualTo(1);
        } else if (decided.status() == ProposalStatus.REJECTED) {
            // 合法结果 B:拒绝胜出——不允许存在任何变更。
            assertThat(decided.decidedBy()).isEqualTo(DECIDED_BY_B);
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
            assertThat(acceptOperations(pending.id())).isZero();
        } else {
            throw new AssertionError(
                    "Illegal terminal state after accept-vs-reject race: " + decided.status());
        }

        assertThat(decided.decidedAt()).isNotNull();
    }

    @Test
    void rejectVsExpireHasSingleTerminalTransition() throws Exception {
        AgentProposal pending = createPendingNodeProposal();

        Attempt[] attempts = race(
                () -> {
                    proposalService.rejectProposal(pending.id(), DECIDED_BY_A);
                    return null;
                },
                () -> {
                    proposalService.expireProposal(pending.id());
                    return null;
                });
        assertSingleWinner(attempts);

        AgentProposal decided = reload(pending.id());

        // 只有一个终态存活;decidedBy 必须与胜出的迁移一致,证明输家从未覆盖任何内容。
        if (decided.status() == ProposalStatus.REJECTED) {
            assertThat(decided.decidedBy()).isEqualTo(DECIDED_BY_A);
        } else if (decided.status() == ProposalStatus.EXPIRED) {
            assertThat(decided.decidedBy()).isEqualTo("system");
        } else {
            throw new AssertionError(
                    "Illegal terminal state after reject-vs-expire race: " + decided.status());
        }
        assertThat(decided.decidedAt()).isNotNull();
    }
}
