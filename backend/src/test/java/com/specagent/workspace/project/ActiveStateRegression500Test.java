package com.specagent.workspace.project;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:ActiveStateRegression500Test.java
 *
 * 测试目标:锁定历史上"路线变更后 500"的症状(见
 * {@code BUG_ROOT_CAUSE_INVESTIGATION.md} BUG-02)。每个路线变更命令之后,
 * 下一次 {@code GET /api/v1/projects/{id}/active} 必须成功返回完整的活跃
 * 状态——绝不 500、绝不返回残缺响应。本测试存在的意义:未来任何重新引入
 * record 风格序列化缺口或命令路径未处理 {@code IllegalStateException} 的
 * 改动都会被立即捕获。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActiveStateRegression500Test {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void activeStateAfterActivateNeverReturns500() throws Exception {
        Project project = projectService.createProject("Regression 500 — activate");
        Route other = routeService.createRoute(project.id(),
                com.specagent.workspace.route.RouteLifecycleStatus.OPEN, "sibling open route");

        mockMvc.perform(post("/api/v1/projects/{pid}/routes/{rid}/activate",
                        project.id(), other.id()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{pid}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(other.id().toString()))
                .andExpect(jsonPath("$.activeRoute.isActive").value(true))
                .andExpect(jsonPath("$.activeNode").doesNotExist());
    }

    @Test
    void activeStateAfterArchiveNeverReturns500() throws Exception {
        Project project = projectService.createProject("Regression 500 — archive");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Will be archived", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/routes/{rid}/archive",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{pid}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.id").value(project.id().toString()))
                .andExpect(jsonPath("$.activeRoute").doesNotExist())
                .andExpect(jsonPath("$.activeNode").doesNotExist());
    }

    @Test
    void activeStateAfterSoftDeleteNeverReturns500() throws Exception {
        Project project = projectService.createProject("Regression 500 — delete");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Will be deleted", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/routes/{rid}/delete",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{pid}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute").doesNotExist())
                .andExpect(jsonPath("$.activeNode").doesNotExist());
    }

    @Test
    void activeStateAfterAnswerSubmissionNeverReturns500() throws Exception {
        Project project = projectService.createProject("Regression 500 — answer");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "To be answered", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(),
                null, "free-text answer content", "test-user");

        mockMvc.perform(get("/api/v1/projects/{pid}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.activeNode.id").value(root.id().toString()));
    }

    @Test
    void activeStateAfterRestoreFromArchiveNeverReturns500() throws Exception {
        Project project = projectService.createProject("Regression 500 — restore");
        Route archived = routeService.createRoute(project.id(),
                com.specagent.workspace.route.RouteLifecycleStatus.ARCHIVED, "archived");

        mockMvc.perform(post("/api/v1/projects/{pid}/routes/{rid}/restore",
                        project.id(), archived.id()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/{pid}/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRoute.id").value(archived.id().toString()))
                .andExpect(jsonPath("$.activeRoute.lifecycleStatus").value("open"));
    }
}
