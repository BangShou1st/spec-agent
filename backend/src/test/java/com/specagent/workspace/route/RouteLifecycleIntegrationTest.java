package com.specagent.workspace.route;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:RouteLifecycleIntegrationTest.java
 *
 * 测试目标:路线生命周期服务的集成测试——激活指针只能指向 OPEN 路线
 * (DELETED/ARCHIVED/SUPERSEDED/跨项目均被拒绝)、归档与软删除清除活跃指针
 * 且不做隐式路线选择、恢复重新打开并激活路线、软删除保留节点/回答/补丁,
 * 以及 ContextBuilder 对损坏的活跃路线状态快速失败。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RouteLifecycleIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private ContextBuilder contextBuilder;

    private record Fixture(Project project, UUID routeId, Node root, Answer answer, AnswerPatch patch) {
    }

    private Fixture createProjectWithData() {
        Project project = projectService.createProject("Route lifecycle project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "What are you clarifying?",
                null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), routeId, root.id(), null,
                "A vague idea", "user");
        Claim claim = Claim.of(ClaimKind.GOAL, "Clarify the idea", ClaimStatus.CONFIRMED,
                root.id(), answer.id());
        AnswerPatch patch = answerPatchService.save(project.id(), routeId, root.id(), answer.id(),
                List.of(claim), null);
        return new Fixture(project, routeId, root, answer, patch);
    }

    @Test
    void setActiveRouteRejectsDeletedRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.DELETED, "deleted");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsArchivedRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.ARCHIVED, "archived");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsSupersededRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.SUPERSEDED, "superseded");

        assertThatThrownBy(() -> routeService.setActiveRoute(f.project().id(), other.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setActiveRouteRejectsRouteFromAnotherProject() {
        Fixture f1 = createProjectWithData();
        Fixture f2 = createProjectWithData();

        assertThatThrownBy(() -> routeService.setActiveRoute(f1.project().id(), f2.routeId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archiveActiveRouteClearsActiveRoute() {
        Fixture f = createProjectWithData();

        routeService.archiveRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isNull();
        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);
    }

    /**
     * 归档活跃路线时即使存在另一条 OPEN 路线也要清除活跃指针。RouteService
     * 绝不能自动选择另一条 OPEN 路线——由调用方显式决定下一条活跃路线。
     * 这锁定"无隐式选择"契约与活跃路线不变量。
     */
    @Test
    void archiveActiveRouteClearsPointerAndDoesNotAutoSelectAnotherOpenRoute() {
        Fixture f = createProjectWithData();
        Route other = routeService.createRoute(f.project().id(), RouteLifecycleStatus.OPEN, "另一条 OPEN 路线");

        routeService.archiveRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId())
                .as("archiving the active route must clear the pointer, not auto-pick another OPEN route")
                .isNull();
        Route archived = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(archived.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);
        Route stillOpen = routeService.getRoute(other.id()).orElseThrow();
        assertThat(stillOpen.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void softDeleteActiveRouteClearsActiveRoute() {
        Fixture f = createProjectWithData();

        routeService.softDeleteRoute(f.project().id(), f.routeId());

        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isNull();
        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.DELETED);
    }

    @Test
    void restoreRouteReopensAndActivatesRoute() {
        Fixture f = createProjectWithData();
        routeService.archiveRoute(f.project().id(), f.routeId());
        assertThat(projectService.getProject(f.project().id()).orElseThrow().activeRouteId()).isNull();

        routeService.restoreRoute(f.project().id(), f.routeId());

        Route route = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        Project project = projectService.getProject(f.project().id()).orElseThrow();
        assertThat(project.activeRouteId()).isEqualTo(f.routeId());
    }

    @Test
    void softDeleteDoesNotDeleteNodesAnswersOrPatches() {
        Fixture f = createProjectWithData();

        routeService.softDeleteRoute(f.project().id(), f.routeId());

        assertThat(nodeService.getNode(f.root().id())).isPresent();
        assertThat(answerService.getAnswer(f.answer().id())).isPresent();
        List<AnswerPatch> patches = answerPatchService.findByRoute(f.routeId());
        assertThat(patches).extracting(AnswerPatch::id).contains(f.patch().id());
    }

    @Test
    void contextBuilderRejectsDeletedActiveRoute() {
        Fixture f = createProjectWithData();
        routeService.softDeleteRoute(f.project().id(), f.routeId());
        forceActiveRoute(f.project().id(), f.routeId());

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextBuilderRejectsArchivedActiveRoute() {
        Fixture f = createProjectWithData();
        routeService.archiveRoute(f.project().id(), f.routeId());
        forceActiveRoute(f.project().id(), f.routeId());

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextBuilderRejectsSupersededActiveRoute() {
        // 通过 regenerate 操作制造一条 SUPERSEDED 路线。
        Fixture f = createProjectWithData();
        // 需要一个 child 节点作为 regenerate 的来源;先创建一个。
        Node child = nodeService.createChildNode(f.project().id(), f.routeId(), f.root().id(),
                "Child question", null, List.of(), true);
        // regenerate 使原路线变为 SUPERSEDED。
        RegenerateResult result = routeService.commitReplacementFromNode(
                f.project().id(), f.routeId(), child.id(), child.id(), null,
                "New question", "New purpose", List.of(), true);

        // 旧路线现在是 SUPERSEDED。
        Route supersededRoute = routeService.getRoute(f.routeId()).orElseThrow();
        assertThat(supersededRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // 强行把 SUPERSEDED 路线置为活跃(模拟非法状态)。
        forceActiveRoute(f.project().id(), f.routeId());

        // ContextBuilder 应当拒绝它。
        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                f.project().id(), null, ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 直接把活跃路线指针重指到非 OPEN 路线,模拟 ContextBuilder 必须拒绝的
     * 非法项目状态。不能使用 RouteService.setActiveRoute,因为它会正确地
     * 拒绝非 OPEN 路线。
     */
    private void forceActiveRoute(UUID projectId, UUID routeId) {
        projectRepository.updateActiveRoute(projectId, routeId, Instant.now());
    }
}
