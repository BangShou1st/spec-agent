package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressRecorder;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RunProgressApiIntegrationTest.java
 *
 * 测试目标:为前端进行中 run 注册表提供运行进度可见性——run 读视图与 active-runs
 * 列表都暴露白名单内的进度(阶段 + 组合的摘要步骤),且 active 列表只包含非终态 run。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RunProgressApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private AgentRunService agentRunService;
    @Autowired private RunProgressRecorder progressRecorder;

    private Project project;
    private UUID routeId;
    private UUID runId;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("运行进度可视化测试");
        routeId = project.activeRouteId();
        runId = agentRunService.create(project.id(), routeId,
                AgentRunTriggerType.ANSWER_CYCLE, null, null).id();
    }

    @Test
    void runViewExposesWhitelistedProgress() throws Exception {
        progressRecorder.noteWithItems(runId, AgentRunPhase.STATE_UPDATED,
                "需求要点整理完成，共 2 条", java.util.List.of("要点一", "要点二"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.phase").value(AgentRunPhase.STATE_UPDATED.code()))
                .andExpect(jsonPath("$.progress.summary").value("需求要点整理完成，共 2 条"))
                .andExpect(jsonPath("$.progress.steps[-1].event")
                        .value(RunProgressRecorder.PROCESS_NOTE_EVENT))
                .andExpect(jsonPath("$.progress.steps[-1].items[0]").value("要点一"));
    }

    @Test
    void activeRunsListingContainsOnlyNonTerminalRunsWithProgress() throws Exception {
        progressRecorder.note(runId, AgentRunPhase.DECIDING, "已确定下一步动作");

        String body = mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/active",
                        project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].runId").value(runId.toString()))
                .andExpect(jsonPath("$[0].progress.summary").value("已确定下一步动作"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("payload");
    }

    @Test
    void completedRunDropsOutOfActiveListing() throws Exception {
        agentRunService.complete(runId, AgentRunStatus.COMPLETED, "completed");

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/active", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
