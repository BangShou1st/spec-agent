package com.specagent.workspace.graph;

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
import org.springframework.dao.DuplicateKeyException;
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
 * 文件名:RelationCreationConcurrencyIntegrationTest.java
 *
 * 测试目标:语义关系创建的并发正确性。项目级行锁串行化关系创建,使两个
 * 竞争事务都读到稳定的关系图:
 *
 * - 因果互反边并发(A DEPENDS_ON B 与 B DEPENDS_ON A 竞争):恰好一条
 *       落库,失败方以成环被拒,活跃因果 DAG 保持无环——绝不出两条边。
 * - 对称重复(A RELATED_TO B 与 B RELATED_TO A 竞争):恰好一条落库,
 *       失败方得到受控冲突,而不是原始 {@link DuplicateKeyException} / 500。 *
 * 刻意不使用 {@code @Transactional}:竞争线程运行在真实、相互独立的
 * 事务中,因此准备数据需要先提交落库。
 */
@SpringBootTest
@ActiveProfiles("test")
class RelationCreationConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("关系并发 " + UUID.randomUUID());
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        nodeA = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "A"));
        nodeB = commandService.appendContinuation(
                project.id(), route.id(), nodeA.id(), "NOTE", Map.of("text", "B")).node();
    }

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
     * 因果互反边并发竞争:A DEPENDS_ON B 对 B DEPENDS_ON A。项目锁把
     * 图读取 + 成环校验串行化,因此恰好一条边落库,失败方以
     * RELATION_DEPENDENCY_CYCLE 失败。
     */
    @Test
    void concurrentOppositeDependencyEdgesKeepExactlyOneAcyclicEdge() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> forward = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeA.id(), nodeB.id(), NodeRelationType.DEPENDS_ON,
                        NodeRelation.Origin.USER, null, null));
            });
            Future<Attempt> reverse = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeB.id(), nodeA.id(), NodeRelationType.DEPENDS_ON,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt attemptA = forward.get(60, TimeUnit.SECONDS);
            Attempt attemptB = reverse.get(60, TimeUnit.SECONDS);

            // 恰好一条边落库。
            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one causal relation must be created")
                    .isTrue();
            // 失败方以成环被拒——它观察到了获胜方写入的边。
            Attempt loser = attemptA.success ? attemptB : attemptA;
            assertThat(loser.error)
                    .isInstanceOf(com.specagent.workspace.graph.GraphRuleViolationException.class)
                    .hasMessageContaining("RELATION_DEPENDENCY_CYCLE");

            // 绝不出现两条边;活跃因果 DAG 是一条无环边。
            List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
            assertThat(active).hasSize(1);
            assertThat(active.get(0).relationType()).isEqualTo(NodeRelationType.DEPENDS_ON);
            // 落库的方向是两条竞争边之一。
            assertThat(active.get(0).sourceNodeId()).isIn(nodeA.id(), nodeB.id());
            assertThat(active.get(0).targetNodeId()).isIn(nodeA.id(), nodeB.id());
            assertThat(active.get(0).sourceNodeId()).isNotEqualTo(active.get(0).targetNodeId());
            // 只有获胜方写入了操作日志。
            assertThat(commandService.listOperations(project.id())
                    .stream().filter(op -> op.type() == GraphOperation.Type.CREATE_SEMANTIC_RELATION)
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 对称重复双向并发竞争:A RELATED_TO B 对 B RELATED_TO A,二者会规范化到
     * 相同的端点。恰好一条落库,失败方得到受控冲突——绝不出现原始
     * {@link DuplicateKeyException}(那会表现为 HTTP 500)。
     */
    @Test
    void concurrentSymmetricDuplicateIsAControlledConflictNever500() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> ab = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                        NodeRelation.Origin.USER, null, null));
            });
            Future<Attempt> ba = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt attemptAb = ab.get(60, TimeUnit.SECONDS);
            Attempt attemptBa = ba.get(60, TimeUnit.SECONDS);

            // 恰好一条落库。
            assertThat(attemptAb.success ^ attemptBa.success)
                    .as("exactly one symmetric relation must be created")
                    .isTrue();
            // 失败方是受控的重复冲突(409 语义),绝不是原始
            // DuplicateKeyException(500)。
            Attempt loser = attemptAb.success ? attemptBa : attemptAb;
            assertThat(loser.error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
            assertThat(loser.error).isNotInstanceOf(DuplicateKeyException.class);

            List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
            assertThat(active).hasSize(1);
            assertThat(active.get(0).relationType()).isEqualTo(NodeRelationType.RELATED_TO);
            // 无论方向如何,都规范化到同一端点顺序。
            assertThat(active.get(0).sourceNodeId())
                    .isEqualTo(nodeA.id().compareTo(nodeB.id()) <= 0 ? nodeA.id() : nodeB.id());
            assertThat(active.get(0).targetNodeId())
                    .isEqualTo(nodeA.id().compareTo(nodeB.id()) <= 0 ? nodeB.id() : nodeA.id());
            assertThat(commandService.listOperations(project.id())
                    .stream().filter(op -> op.type() == GraphOperation.Type.CREATE_SEMANTIC_RELATION)
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
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