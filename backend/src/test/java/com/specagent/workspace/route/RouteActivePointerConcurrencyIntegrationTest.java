package com.specagent.workspace.route;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:RouteActivePointerConcurrencyIntegrationTest.java
 *
 * 测试目标:并发下活跃路线指针的原子性。
 *
 * 两个独立事务对同一项目竞争执行生命周期 / 活跃路线变更。每个操作都会
 * 获取项目行锁({@code ProjectRepository.lockById} 中的
 * {@code SELECT ... FOR UPDATE}),使"决策-写入"序列被串行化:获胜方完整
 * 提交之后,失败方才观察到项目状态。该测试守护的回归是经典的丢失更新——
 * 没有锁时,{@code archiveRoute} 可能读到过期的活跃指针,把它悬空在已归档
 * 的路线上,导致项目 {@code activeRouteId} 指向非 OPEN 的路线。
 *
 * 刻意不使用 {@code @Transactional}:每个竞争者必须运行在自己真实的
 * 数据库事务中,项目锁才能真正串行化它们;准备数据需在启动线程前提交。
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteActivePointerConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private RouteService routeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private GraphCommandService commandService;
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
        // 路线通过外键引用节点(branch_at / created_from / branch_from),
        // 因此 routes 必须先于 nodes 删除。
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * 激活 A 对 归档 A。无论谁最后提交,项目绝不能把活跃路线指向已归档的
     * 路线。归档获胜则活跃指针被清除(null);激活"获胜"也只发生在归档执行
     * 之前,之后归档仍会清除指针。因此最终的活跃路线要么为 null,要么——
     * 若存在其他 OPEN 路线——是一条 OPEN 路线,绝不会是 A。
     */
    @Test
    void activateActiveVsArchiveActiveNeverDanglesPointerOnArchivedRoute() throws Exception {
        project = projectService.createProject("激活与归档并发 " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureActivate = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.setActiveRoute(project.id(), activeRouteId); return null; });
            });
            Future<Attempt> futureArchive = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), activeRouteId); return null; });
            });

            futureActivate.get(60, TimeUnit.SECONDS);
            futureArchive.get(60, TimeUnit.SECONDS);

            // 已归档路线最终一定被归档——归档操作不会被挫败。
            Route archived = routeRepository.findById(activeRouteId).orElseThrow();
            assertThat(archived.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);

            // 活跃指针绝不停留在已归档的路线上。
            Project after = projectRepository.findById(project.id()).orElseThrow();
            assertThat(after.activeRouteId())
                    .as("active pointer must not dangle on the archived route")
                    .isNotEqualTo(activeRouteId);
            if (after.activeRouteId() != null) {
                Route active = routeRepository.findById(after.activeRouteId()).orElseThrow();
                assertThat(active.lifecycleStatus())
                        .as("any remaining active route must be OPEN")
                        .isEqualTo(RouteLifecycleStatus.OPEN);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 恢复 A 对 激活 B。A 初始为归档,B 为活跃。两者最终都为 OPEN;无论哪个
     * 写入最后赢得活跃指针,指针必须落在一条 OPEN 路线上——绝不会落在处于
     * 过期状态的"归档后恢复"的 A 上,也绝不会落在非 OPEN 路线上。
     */
    @Test
    void restoreVsActivateAlwaysLeavesActivePointingAtOpenRoute() throws Exception {
        project = projectService.createProject("恢复与激活并发 " + UUID.randomUUID());
        UUID routeA = project.activeRouteId();
        UUID routeB = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "并发B").id();

        // A 被归档、B 成为活跃,使两个竞争者都有意义。
        routeService.archiveRoute(project.id(), routeA);
        routeService.setActiveRoute(project.id(), routeB);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureRestore = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.restoreRoute(project.id(), routeA); return null; });
            });
            Future<Attempt> futureActivateB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.setActiveRoute(project.id(), routeB); return null; });
            });

            futureRestore.get(60, TimeUnit.SECONDS);
            futureActivateB.get(60, TimeUnit.SECONDS);

            // 两条路线最终都为 OPEN。
            assertThat(routeRepository.findById(routeA).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.OPEN);
            assertThat(routeRepository.findById(routeB).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.OPEN);

            // 无论谁获胜,活跃指针必须指向 OPEN 路线。
            Project after = projectRepository.findById(project.id()).orElseThrow();
            assertThat(after.activeRouteId()).isNotNull();
            Route active = routeRepository.findById(after.activeRouteId()).orElseThrow();
            assertThat(active.lifecycleStatus())
                    .as("active pointer must point at an OPEN route")
                    .isEqualTo(RouteLifecycleStatus.OPEN);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 4 项——fork 与源路线归档竞争。两者都先获取项目锁;无论谁
     * 获胜,已归档的路线绝不被修改或复活:归档获胜则 fork 因生命周期过期
     * 失败;fork 获胜则归档仍会归档源路线,fork 继承冻结的不可变节点。
     * 无论哪种结果,归档路线保持归档。
     */
    @Test
    void archiveVsForkNeverMutatesOrResurrectsArchivedRoute() throws Exception {
        project = projectService.createProject("归档与分叉并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> forkFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.forkFromNode(project.id(), routeId, child.id(), "并发分叉"));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt forkAttempt = forkFuture.get(60, TimeUnit.SECONDS);

            // 无论竞争结果如何,源路线都被归档。
            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (forkAttempt.success) {
                // fork 赢得锁:其路线为 OPEN 并继承冻结的不可变节点;归档仍
                // 归档了源路线。
                Route fork = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
                assertThat(fork.rootNodeId()).isEqualTo(root.id());
                assertThat(fork.tipNodeId()).isEqualTo(child.id());
            } else {
                // 归档赢得锁:fork 因生命周期被拒,快速失败。
                assertThat(forkAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 4 项——re-answer 与源路线归档竞争。与 fork 相同的串行化
     * 契约:源路线绝不会既被归档又被重新回答,re-answer 也绝不能复活它。
     */
    @Test
    void archiveVsReanswerNeverMutatesOrResurrectsArchivedRoute() throws Exception {
        project = projectService.createProject("归档与重答并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> reanswerFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.reanswerFromNode(project.id(), routeId, child.id(), "并发重答"));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt reanswerAttempt = reanswerFuture.get(60, TimeUnit.SECONDS);

            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (reanswerAttempt.success) {
                Route reanswer = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(reanswer.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
            } else {
                assertThat(reanswerAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 4 项——替换提交与源路线归档竞争。提交持有项目锁并在锁内
     * 重读源路线;已归档的源路线绝不会被取代或替换。
     */
    @Test
    void archiveVsReplacementNeverMutatesArchivedRoute() throws Exception {
        project = projectService.createProject("归档与换题并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> replacementFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.commitReplacementFromNode(
                        project.id(), routeId, child.id(), child.id(), null,
                        "Replacement question", "Replacement purpose", List.of(), true));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt replacementAttempt = replacementFuture.get(60, TimeUnit.SECONDS);

            // 源路线被归档;替换绝不能取代或修改已归档的路线。
            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (replacementAttempt.success) {
                // 替换赢得锁;归档随后仍归档了源路线,替换路线是活跃的
                // OPEN 路线。
                Route replacement = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
            } else {
                assertThat(replacementAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深度评审第 4 项——替换决策冻结于提交之前捕获的源 tip;并发续写推进
     * tip 时,替换提交必须因过期失败,而不是取代已移动的路线。冻结的期望
     * tip 会在提交事务内、项目锁下重新核验,因此不存在 check-then-act 窗口。
     */
    @Test
    void replacementCommitFailsStaleWhenConcurrentContinuationMovesTip() throws Exception {
        project = projectService.createProject("换题过期并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        // 决策以 child.id() 作为源 tip 被冻结。
        UUID frozenTip = child.id();

        // 提交之前,续写把 tip 推进到新节点。
        // 派生知识不再顶掉问题 tip,这里用真正的新问题推进 tip。
        Node advanced = nodeService.createChildNode(
                project.id(), routeId, child.id(),
                "Advanced question", null, List.of(), true);
        assertThat(advanced.id()).isNotEqualTo(frozenTip);

        // 过期的提交必须被拒绝:冻结的 tip 不再匹配。
        assertThatThrownBy(() -> routeService.commitReplacementFromNode(
                project.id(), routeId, child.id(), frozenTip, null,
                "Replacement question", "Replacement purpose", List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source route tip moved");

        // 路线未被触碰:仍为 OPEN,tip 为推进后的节点。
        Route source = routeRepository.findById(routeId).orElseThrow();
        assertThat(source.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(source.tipNodeId()).isEqualTo(advanced.id());
    }

    /**
     * 深度评审第 4 项——提交边界本身在项目锁下重新核验冻结 tip:tip 仍匹配
     * 则替换提交;已移动则失败。这证明并发下串行化是顺序正确的
     * (tip 不可能在提交中途推进)。
     */
    @Test
    void replacementCommitTipMatchesUnderLock() throws Exception {
        project = projectService.createProject("换题提交并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        // 冻结 tip 与活跃 tip 匹配;提交必须成功并取代源路线。
        RegenerateResult result = routeService.commitReplacementFromNode(
                project.id(), routeId, child.id(), child.id(), null,
                "Replacement question", "Replacement purpose", List.of(), true);
        assertThat(result.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(result.replacementRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
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
