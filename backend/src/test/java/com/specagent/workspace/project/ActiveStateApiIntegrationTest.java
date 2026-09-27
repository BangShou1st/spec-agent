package com.specagent.workspace.project;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:ActiveStateApiIntegrationTest.java
 *
 * 测试目标:项目活跃状态接口(/active)的集成测试。状态视图必须只跟随
 * {@code Project.activeRouteId},绝不凭空捏造初始节点,也绝不泄漏
 * 兄弟路线的数据。
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
    @Autowired
    private ProjectRepository projectRepository;

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

        // 拥有自己节点的兄弟 open 路线绝不出现在项目的活跃状态里。
        Route sibling = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "sibling route");
        Node siblingNode = nodeService.createRootNode(
                project.id(), sibling.id(), "Sibling question", null, List.of(), true);

        mockMvc.perform(get("/api/v1/projects/{id}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.activeNode.id").value(root.id().toString()))
                .andExpect(jsonPath("$.activeNode.question").value("Active root"));
        // 兄弟路线不是活跃路线,其节点也不是 tip。
        assertThat(siblingNode.id()).isNotEqualTo(root.id());
        assertThat(sibling.id()).isNotEqualTo(project.activeRouteId());
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}/active", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    /**
     * 防御性快速失败守卫:若 {@code Project.activeRouteId} 解析到了属于其他
     * 项目的路线(不变量被破坏),活跃状态读取必须安全失败,既不暴露外部
     * 路线,也不暴露其节点。
     */
    @Test
    void crossProjectActivePointerFailsClosedWithoutExposure() throws Exception {
        Project owner = projectService.createProject("Pointer owner");
        Project other = projectService.createProject("Pointer other");
        Node otherNode = nodeService.createRootNode(
                other.id(), other.activeRouteId(), "Foreign node content", null, List.of(), true);

        // 破坏不变量:owner 的活跃指针指向 other 的路线。
        projectRepository.updateActiveRoute(owner.id(), other.activeRouteId(), java.time.Instant.now());

        mockMvc.perform(get("/api/v1/projects/{id}/active", owner.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"))
                .andExpect(jsonPath("$.message").value("The active route does not belong to the project"))
                .andExpect(jsonPath("$.project").doesNotExist())
                .andExpect(jsonPath("$.activeRoute").doesNotExist())
                .andExpect(jsonPath("$.activeNode").doesNotExist());

        // 响应中任何位置都不暴露外部记录。
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{id}/active", owner.id()))
                .andExpect(status().isInternalServerError())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(otherNode.id().toString())
                .doesNotContain(other.activeRouteId().toString());
    }
}