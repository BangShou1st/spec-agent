package com.specagent.workspace.route;

import com.specagent.common.Ids;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RouteApiIntegrationTest.java
 *
 * 测试目标:路线读取 API 的集成测试——读取绝不改变路线生命周期,
 * 也绝不引入 {@code active} 生命周期状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;

    @Test
    void listProjectRoutesIdentifiesActiveRoute() throws Exception {
        Project project = projectService.createProject("Route listing");
        var superseded = routeService.createRoute(
                project.id(), RouteLifecycleStatus.SUPERSEDED, "old superseded route");

        mockMvc.perform(get("/api/v1/projects/{id}/routes", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$[0].lifecycleStatus").value("open"))
                .andExpect(jsonPath("$[0].isActive").value(true))
                .andExpect(jsonPath("$[1].id").value(superseded.id().toString()))
                .andExpect(jsonPath("$[1].lifecycleStatus").value("superseded"))
                .andExpect(jsonPath("$[1].isActive").value(false));
    }

    @Test
    void routeLifecycleSerializedAsCode() throws Exception {
        Project project = projectService.createProject("Lifecycle serialization");
        var archived = routeService.createRoute(project.id(), RouteLifecycleStatus.ARCHIVED, "archived");
        var deleted = routeService.createRoute(project.id(), RouteLifecycleStatus.DELETED, "deleted");

        mockMvc.perform(get("/api/v1/projects/{id}/routes", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].lifecycleStatus").value("archived"))
                .andExpect(jsonPath("$[2].lifecycleStatus").value("deleted"))
                .andExpect(jsonPath("$[*].lifecycleStatus", not(hasItem("active"))));
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/routes", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void readDoesNotMutateRouteLifecycle() throws Exception {
        Project project = projectService.createProject("Read stability");
        routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "extra open route");

        mockMvc.perform(get("/api/v1/projects/{id}/routes", project.id()))
                .andExpect(status().isOk());

        // 读取之后,活跃路线仍是原始那条,且没有任何生命周期状态变化。
        var routes = routeService.listRoutes(project.id());
        assertThat(routes.stream().filter(r -> r.lifecycleStatus() == RouteLifecycleStatus.OPEN))
                .hasSize(2);
        assertThat(routes.stream().anyMatch(r -> r.isActive(project.activeRouteId()))).isTrue();
    }
}