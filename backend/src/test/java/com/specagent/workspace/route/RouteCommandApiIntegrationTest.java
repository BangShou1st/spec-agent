package com.specagent.workspace.route;

import com.specagent.common.Ids;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RouteCommandApiIntegrationTest.java
 *
 * 测试目标:路线命令的集成测试——activate、archive、restore、软删除。
 * 所有命令都经由 RouteService;生命周期状态绝不出现 {@code active},
 * 活跃指针始终是 {@code Project.activeRouteId}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteCommandApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;

    @Test
    void activateOpenRouteSucceedsAndChangesActivePointer() throws Exception {
        Project project = projectService.createProject("Activation project");
        // 另一条非活跃的 OPEN 路线。
        var other = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "second open route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), other.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.id").value(other.id().toString()))
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.activeRouteId").value(other.id().toString()));

        // 运行时指针确实变化;生命周期保持 OPEN。
        var projectAfter = projectService.getProject(project.id()).orElseThrow();
        assertThat(projectAfter.activeRouteId()).isEqualTo(other.id());
        assertThat(routeService.getRoute(other.id()).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void activateArchivedRouteRejected() throws Exception {
        Project project = projectService.createProject("Archived activation");
        var archived = routeService.createRoute(project.id(), RouteLifecycleStatus.ARCHIVED, "archived route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), archived.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_ACTIVATABLE"));
    }

    @Test
    void activateDeletedRouteRejected() throws Exception {
        Project project = projectService.createProject("Deleted activation");
        var deleted = routeService.createRoute(project.id(), RouteLifecycleStatus.DELETED, "deleted route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), deleted.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_ACTIVATABLE"));
    }

    @Test
    void activateSupersededRouteRejected() throws Exception {
        Project project = projectService.createProject("Superseded activation");
        var superseded = routeService.createRoute(project.id(), RouteLifecycleStatus.SUPERSEDED, "superseded route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), superseded.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_ACTIVATABLE"));
    }

    @Test
    void activateRouteFromAnotherProjectRejected() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");
        var routeA = routeService.createRoute(projectA.id(), RouteLifecycleStatus.OPEN, "A route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        projectB.id(), routeA.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void activateUnknownRouteRejected() throws Exception {
        Project project = projectService.createProject("Unknown route project");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void archiveOpenRoutePreservesDataAndClearsActivePointerWhenActive() throws Exception {
        Project project = projectService.createProject("Archive project");
        Node node = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Node content preserved after archive", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/archive",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.lifecycleStatus").value("archived"))
                .andExpect(jsonPath("$.route.isActive").value(false));

        // 活跃指针被清除;节点/回答被保留而非删除。
        var projectAfter = projectService.getProject(project.id()).orElseThrow();
        assertThat(projectAfter.activeRouteId()).isNull();
        assertThat(nodeService.getNode(node.id())).isPresent();
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.ARCHIVED);
    }

    @Test
    void restoreMakesRouteOpenAndActive() throws Exception {
        Project project = projectService.createProject("Restore project");
        var archived = routeService.createRoute(project.id(), RouteLifecycleStatus.ARCHIVED, "archived route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/restore",
                        project.id(), archived.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.activeRouteId").value(archived.id().toString()));

        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(archived.id());
    }

    @Test
    void softDeletePreservesHistoricalRecords() throws Exception {
        Project project = projectService.createProject("Soft delete project");
        Node node = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Historical content stays", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/delete",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.lifecycleStatus").value("deleted"))
                .andExpect(jsonPath("$.route.isActive").value(false));

        // 没有物理删除:节点与路线记录仍然存在。
        assertThat(nodeService.getNode(node.id())).isPresent();
        assertThat(routeService.getRoute(project.activeRouteId())).isPresent();
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId()).isNull();
    }

    @Test
    void wrongProjectCommandRejectedForArchive() throws Exception {
        Project projectA = projectService.createProject("Archive owner A");
        Project projectB = projectService.createProject("Archive owner B");
        var routeA = routeService.createRoute(projectA.id(), RouteLifecycleStatus.OPEN, "A route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/archive",
                        projectB.id(), routeA.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }
}