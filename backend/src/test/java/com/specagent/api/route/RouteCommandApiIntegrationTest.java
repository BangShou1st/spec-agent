package com.specagent.api.route;

import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
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
 * Route command integration tests: activate, archive, restore, soft delete.
 * All commands go through RouteService; lifecycle status is never
 * {@code active}, and the active pointer is always {@code Project.activeRouteId}.
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
        // A second OPEN route that is not active.
        var other = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "second open route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/routes/{routeId}/activate",
                        project.id(), other.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.id").value(other.id().toString()))
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.activeRouteId").value(other.id().toString()));

        // The runtime pointer really changed; lifecycle stays OPEN.
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

        // Active pointer cleared; nodes/answers are preserved, not deleted.
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

        // No physical deletion: the node and the route row still exist.
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