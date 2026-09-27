package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.common.Ids;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AgentRunApiIntegrationTest.java
 *
 * 测试目标:AgentRun 读接口的集成测试——问题草稿经异步决策运行时执行(test profile 下
 * 使用确定性假引擎,零公开 provider 请求),读接口只暴露安全元数据与脱敏的 trace 步骤;
 * 覆盖单 run 读取、项目 run 列表、未知 run/项目 404,以及跨项目读取必须不可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentRunApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;

    @Test
    void getRunExposesSafeTraceSteps() throws Exception {
        Project project = projectService.createProject("Run reading");
        AgentRun result = draftDriver.draftQuestion(project.id());
        UUID runId = result.id();

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/projects/{projectId}/runs/{runId}",
                        project.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.triggerType").value(AgentRunTriggerType.DECISION_CYCLE.code()))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.traceSteps", hasSize(1)))
                .andExpect(jsonPath("$.traceSteps[0]")
                        .value("created>context_built>executing>completed"))
                .andExpect(jsonPath("$.producedNodeId").exists())
                .andReturn();

        String body = mvcResult.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("Bearer")
                .doesNotContain("sk-")
                .doesNotContain("apiKey")
                .doesNotContain("inputJson")
                .doesNotContain("outputJson");
    }

    @Test
    void listRunsForProject() throws Exception {
        Project project = projectService.createProject("Run listing");
        draftDriver.draftQuestion(project.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/runs", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("completed"));
    }

    @Test
    void unknownRunReturnsNotFound() throws Exception {
        Project project = projectService.createProject("No runs yet");

        mockMvc.perform(get("/api/v1/projects/{projectId}/runs/{runId}",
                        project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void runFromAnotherProjectCannotBeReadThroughWrongProject() throws Exception {
        Project projectA = projectService.createProject("Owner project");
        draftDriver.draftQuestion(projectA.id());
        UUID runId = agentRunService.listByProject(projectA.id()).get(0).id();

        Project projectB = projectService.createProject("Unrelated project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/runs/{runId}",
                        projectB.id(), runId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void listRunsForUnknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/runs", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}