package com.specagent.workspace.answer;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:AnswerFinalizeConcurrencyIntegrationTest.java
 *
 * 测试目标:验证共享回答的并发正确性——两条路线对同一个权威 Question 节点
 * 并发执行 finalize 时,不允许产生两个 Answer 身份。节点行锁会串行化最终落库:
 * 恰好一个事务提交成功,失败方观察到已持久化的 Answer 并走到
 * SHARED_STATE_DIVERGENCE 冲突分支。
 *
 * 刻意不使用 {@code @Transactional}:两个竞争线程必须运行在真实、相互独立
 * 的事务中,因此准备数据需要在启动线程前先提交落库。
 */
@SpringBootTest
@ActiveProfiles("test")
class AnswerFinalizeConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteService routeService;
    @Autowired private AnswerService answerService;
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

    @Test
    void concurrentFinalizeOnSharedQuestionPersistsExactlyOneAnswer() throws Exception {
        project = projectService.createProject("共享回答并发 " + UUID.randomUUID());
        UUID firstRouteId = project.activeRouteId();
        // 两条路线到达同一个权威 Question 节点。
        Node question = nodeService.createRootNode(
                project.id(), firstRouteId, "共享问题?", null, List.of(), true);
        UUID secondRouteId = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "并发第二条路线").id();
        routeService.updateTip(secondRouteId, question.id(), question.id());

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureA = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), firstRouteId, question.id(), null, "回答A", "user"));
            });
            Future<Attempt> futureB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), secondRouteId, question.id(), null, "回答B", "user"));
            });

            Attempt attemptA = futureA.get(60, TimeUnit.SECONDS);
            Attempt attemptB = futureB.get(60, TimeUnit.SECONDS);

            // 恰好一个事务成功。
            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one finalization must succeed")
                    .isTrue();
            // 失败方走到的预期冲突分支:权威 Question 已携带一个不可变的 Answer 身份。
            Attempt loser = attemptA.success ? attemptB : attemptA;
            assertThat(loser.error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHARED_STATE_DIVERGENCE");

            // 权威节点只应存在一条 Answer 记录——绝不出现两个 Answer 身份。
            assertThat(answerCountFor(question.id())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private long answerCountFor(UUID nodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE node_id = ?", Long.class, nodeId);
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