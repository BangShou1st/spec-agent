package com.specagent.agent.runtime;

import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RoutelessNodeQueryRunViewContractTest.java
 *
 * 测试目标:合法无路线 NodeQuery 的响应契约(R6)。游离节点查询以
 * {@code routeId = null} 入队,通用 run 视图
 * ({@code AgentRunViewResponse.from})绝不能对可为空的 routeId 调用
 * {@code toString()}——此前会抛 NPE,使 {@code GET /agent-runs/{runId}}
 * 与 {@code GET /agent-runs/active} 返回 500,前端刷新时丢失运行进度。
 *
 * 覆盖:浮动节点查询入队(202)、运行期间查询与刷新(活跃列表 + 单查)、
 * 终态查询,以及同项目其他带路线 run 同时存在时的列表契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoutelessNodeQueryRunViewContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;

    @Test
    void routelessQueryRunViewExposesNullRouteIdAcrossItsLifecycle() throws Exception {
        Project project = projectService.createProject("无路线契约 " + UUID.randomUUID());
        Node floating = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "漂浮的想法"));

        // 公开业务 API 入队:routeId 显式为 null
        MvcResult accepted = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/nodes/{nodeId}/query", project.id(), floating.id())
                                .contentType("application/json")
                                .content("{\"routeId\":null,\"question\":\"这个想法为什么重要？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn();
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accepted.getResponse().getContentAsString())
                .get("runId").asText();
        UUID runIdUuid = UUID.fromString(runId);

        // 运行期间(排队中):单查 200 且 routeId 为 null
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}", project.id(), runIdUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.routeId").value(org.hamcrest.Matchers.nullValue()));
        // 活跃列表 200,包含该 run
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runId == '" + runId + "')]").isNotEmpty());

        // 走生产认领 + 执行路径到达终态
        AgentRun claimed = runService.claimNodeQueryRun(runIdUuid).orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runIdUuid).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // 终态查询:routeId 仍为 null,不再是 500
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}", project.id(), runIdUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.routeId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void activeListMixesRoutedAndRoutelessRunsWithoutFailing() throws Exception {
        Project project = projectService.createProject("混合列表 " + UUID.randomUUID());
        Node floating = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "另一个漂浮的想法"));

        // 同项目同时存在:一条带路线的排队 run(起草问题)+ 一条无路线查询
        runService.createQueuedDraftQuestion(project.id());
        UUID queryRunId = runService.createQueuedNodeQuery(
                project.id(), null, floating.id(), "它解决什么问题？");

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runId == '" + queryRunId + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.routeId == null)]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.routeId != null)]").isNotEmpty());

        // 带路线 run 的视图契约不受影响:routeId 仍是字符串
        var draftRun = agentRunService.listActiveByProject(project.id()).stream()
                .filter(r -> r.routeId() != null).findFirst().orElseThrow();
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}", project.id(), draftRun.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(draftRun.routeId().toString()));
    }
}
