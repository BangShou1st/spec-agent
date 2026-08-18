package com.specagent.api.project;

import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Active project state API integration tests. The state view must follow
 * {@code Project.activeRouteId} only, never invent an initial node, and never
 * leak sibling-route data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActiveStateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;

    @Test
    void newProjectHasActiveInitialRouteAndNullActiveNode() throws Exception {
        Project project = projectService.createProject("Fresh project");

        mockMvc.perform(get("/api/v1/projects/{id}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.id").value(project.id().toString()))
                .andExpect(jsonPath("$.project.title").value("Fresh project"))
                .andExpect(jsonPath("$.activeRoute.id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.activeRoute.isActive").value(true))
                .andExpect(jsonPath("$.activeRoute.rootNodeId").isEmpty())
                .andExpect(jsonPath("$.activeRoute.tipNodeId").isEmpty())
                .andExpect(jsonPath("$.activeNode").doesNotExist());
    }

    @Test
    void activeStateReturnsCorrectRouteAndTipNodeAfterDrafting() throws Exception {
        Project project = projectService.createProject("Drafted project");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "What are you clarifying?", null, List.of(), true);

        mockMvc.perform(get("/api/v1/projects/{id}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.activeRoute.tipNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.activeNode.id").value(root.id().toString()))
                .andExpect(jsonPath("$.activeNode.question").value("What are you clarifying?"))
                .andExpect(jsonPath("$.activeNode.parentNodeId").isEmpty());
    }

    @Test
    void noUnrelatedRouteOrNodeLeakage() throws Exception {
        Project project = projectService.createProject("Isolation project");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "Active root", null, List.of(), true);

        // A sibling open route with its own node must never appear in the
        // active state of the project.
        Route sibling = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "sibling route");
        Node siblingNode = nodeService.createRootNode(
                project.id(), sibling.id(), "Sibling question", null, List.of(), true);

        mockMvc.perform(get("/api/v1/projects/{id}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.activeNode.id").value(root.id().toString()))
                .andExpect(jsonPath("$.activeNode.question").value("Active root"));
        // The sibling route is not the active route and its node is not the tip.
        assertThat(siblingNode.id()).isNotEqualTo(root.id());
        assertThat(sibling.id()).isNotEqualTo(project.activeRouteId());
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/active", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}