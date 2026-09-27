package com.specagent.workspace.route;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

/**
 * 文件名:RouteMutationAtomicityIntegrationTest.java
 *
 * 测试目标:re-answer / fork 的事务原子性。两种变更都包含多个持久化写入
 * (路线保存、继承前缀引用、Question 节点 / 活跃指针变更)。路线创建之后的
 * 任何失败都必须回滚整个变更:不留孤儿路线、不留继承引用、不留新 Question,
 * 源路线与先前的活跃指针均不被触碰。
 *
 * 故障通过 spy 注入到继承前缀快照处:先执行真实写入、再抛出异常——因此
 * 该回归证明的是已写入的继承引用也会被回滚,而不仅仅是"写入前失败什么
 * 都不会留下"。
 *
 * 刻意不使用 {@code @Transactional}——只有当服务事务真正对数据库提交/
 * 回滚时,该回归才能证明事务边界。
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteMutationAtomicityIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private RouteService routeService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 对真实解析器的 spy:被注入的方法先执行真实的持久化写入、然后失败,
    // 其余一切保持生产行为。
    @SpyBean private RouteHistoryResolver routeHistoryResolverSpy;

    @Test
    void reanswerRollsBackEveryDurableWriteWhenQuestionCreationFails() {
        Project project = projectService.createProject("Reanswer atomicity " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();
        Node question = nodeService.createRootNode(
                project.id(), activeRouteId, "原始问题?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), activeRouteId, question.id(),
                null, "第一个回答", "user");

        long routesBefore = countRoutes(project.id());
        long nodesBefore = countNodes(project.id());
        long inheritedBefore = countInheritedRefs(project.id());
        Route sourceBefore = routeRepository.findById(activeRouteId).orElseThrow();

        // 在新路线行及其继承前缀写入之后、变更完成之前强制失败:
        // 真实的前缀快照先执行,然后注入点抛出异常。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("reanswer mutation exploded");
        }).when(routeHistoryResolverSpy).snapshotInheritedPrefix(
                any(UUID.class), any(UUID.class), any(UUID.class), anyBoolean());

        assertThatThrownBy(() -> routeService.reanswerFromNode(
                project.id(), activeRouteId, question.id(), "重新回答"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reanswer mutation exploded");

        // 不残留新路线。
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        // 不残留继承引用(它们曾被写入,然后被回滚)。
        assertThat(countInheritedRefs(project.id())).isEqualTo(inheritedBefore);
        // 不残留新 Question。
        assertThat(countNodes(project.id())).isEqualTo(nodesBefore);
        // 源路线不变。
        Route sourceAfter = routeRepository.findById(activeRouteId).orElseThrow();
        assertThat(sourceAfter.tipNodeId()).isEqualTo(sourceBefore.tipNodeId());
        assertThat(sourceAfter.rootNodeId()).isEqualTo(sourceBefore.rootNodeId());
        assertThat(sourceAfter.lifecycleStatus()).isEqualTo(sourceBefore.lifecycleStatus());
        // 先前的活跃指针不变(仍是源路线)。
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);
    }

    @Test
    void forkRollsBackRouteAndInheritedRefsWhenPrefixSnapshotFails() {
        Project project = projectService.createProject("Fork atomicity " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();
        Node question = nodeService.createRootNode(
                project.id(), activeRouteId, "分支问题?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), activeRouteId, question.id(),
                null, "分支回答", "user");

        long routesBefore = countRoutes(project.id());
        long inheritedBefore = countInheritedRefs(project.id());
        Route sourceBefore = routeRepository.findById(activeRouteId).orElseThrow();

        // 在变更中途强制失败:fork 路线行及其继承前缀已写入,然后在活跃指针
        // 移动之前写入失败。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("fork mutation exploded");
        }).when(routeHistoryResolverSpy).snapshotInheritedPrefix(
                any(UUID.class), any(UUID.class), any(UUID.class), anyBoolean());

        assertThatThrownBy(() -> routeService.forkFromNode(
                project.id(), activeRouteId, question.id(), "分支"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fork mutation exploded");

        // 不残留新路线。
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        // 不残留继承引用(它们曾被写入,然后被回滚)。
        assertThat(countInheritedRefs(project.id())).isEqualTo(inheritedBefore);
        // 源路线不变。
        Route sourceAfter = routeRepository.findById(activeRouteId).orElseThrow();
        assertThat(sourceAfter.tipNodeId()).isEqualTo(sourceBefore.tipNodeId());
        assertThat(sourceAfter.rootNodeId()).isEqualTo(sourceBefore.rootNodeId());
        // 先前的活跃指针不变(活跃指针更新是最后一个持久化写入,因此变更中途
        // 的失败绝不能移动它)。
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);
    }

    private long countRoutes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routes WHERE project_id = ?", Long.class, projectId);
    }

    private long countNodes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ?", Long.class, projectId);
    }

    private long countInheritedRefs(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_inherited_answers "
                        + "WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)",
                Long.class, projectId);
    }
}