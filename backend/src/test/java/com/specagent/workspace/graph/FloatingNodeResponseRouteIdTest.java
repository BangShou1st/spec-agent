package com.specagent.workspace.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:FloatingNodeResponseRouteIdTest.java
 *
 * 测试目标:验证游离节点创建接口的响应形态——游离草稿是不挂在路线上的
 * 图内容,响应中的 {@code routeId} 必须为空(不出现)。创建上下文中的路线 id
 * 仍会记录在操作日志里,改变的只是响应结构。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FloatingNodeResponseRouteIdTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private com.specagent.workspace.route.RouteService routeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Project project;
    private Route route;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        project = projectService.createProject("Floating Response RouteId 测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    @Test
    void floatingNodeResponseHasNullRouteId() throws Exception {
        // 先添加一个根节点让路线有内容;游离草稿始终不挂路线,
        // 与是否已有内容无关。
        commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));

        mockMvc.perform(post("/api/v1/projects/{pid}/floating-nodes", project.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of(
                                        "routeId", route.id().toString(),
                                        "subtype", "IDEA",
                                        "content", Map.of("text", "a floating idea")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.routeId").doesNotExist());
    }

    @Test
    void floatingNodeCanBeCreatedWithoutAnyActiveRoute() throws Exception {
        // 归档项目唯一的路线,使 activeRouteId 变为 null:
        // 游离创建不能硬依赖存在 Active 路线。
        routeService.archiveRoute(project.id(), route.id());
        org.junit.jupiter.api.Assertions.assertNull(
                projectService.getProject(project.id()).orElseThrow().activeRouteId());

        mockMvc.perform(post("/api/v1/projects/{pid}/floating-nodes", project.id())
                        .contentType("application/json")
                        .content("{\"subtype\":\"IDEA\",\"content\":{\"text\":\"idea without any route\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.routeId").doesNotExist());
        // 路线 tip / 根节点 / 活跃指针均未被改动。
        org.junit.jupiter.api.Assertions.assertNull(
                projectService.getProject(project.id()).orElseThrow().activeRouteId());
    }
}
