package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.loop.ContinuationCheckRepository;
import com.specagent.agent.loop.LoopLinkage;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closure B read-contract tests: the plain run polling view exposes the
 * autonomous chain facts (direct child, pending continuation check, latest
 * RESPOND_MESSAGE) without executing anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentRunChainReadApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private ContinuationCheckRepository checkRepository;

    @Test
    void parentCompletedWithChildExposesChildRunId() throws Exception {
        Project project = projectService.createProject("Chain read B1");
        AgentRun parent = agentRunService.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.DECISION_CYCLE, null, null, "DRAFT_QUESTION");
        agentRunService.complete(parent.id(), AgentRunStatus.COMPLETED, "done");
        AgentRun child = agentRunService.createWithIdempotency(project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, "CONTINUE", null, null,
                new LoopLinkage(parent.id(), parent.id(), 1)).run();

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), parent.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childRunId").value(child.id().toString()))
                .andExpect(jsonPath("$.continuationPending").value(false));
    }

    @Test
    void parentCompletedWithPendingCheckAndNoChildReportsPending() throws Exception {
        Project project = projectService.createProject("Chain read B2");
        AgentRun parent = agentRunService.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.DECISION_CYCLE, null, null, "DRAFT_QUESTION");
        agentRunService.complete(parent.id(), AgentRunStatus.COMPLETED, "done");
        checkRepository.request(parent.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), parent.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childRunId").doesNotExist())
                .andExpect(jsonPath("$.continuationPending").value(true));
    }

    @Test
    void leafRespondRunExposesLatestRespondMessage() throws Exception {
        Project project = projectService.createProject("Chain read B3");
        AgentRun leaf = agentRunService.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.DECISION_CYCLE, null, null, "DRAFT_QUESTION");
        agentRunService.complete(leaf.id(), AgentRunStatus.COMPLETED, "responded");
        eventService.append(leaf.id(), AgentRunPhase.COMPLETED,
                com.specagent.agent.runevent.AgentRunEventTypes.RESPOND_MESSAGE_EVENT,
                Map.of("message", "first"));
        eventService.append(leaf.id(), AgentRunPhase.COMPLETED,
                com.specagent.agent.runevent.AgentRunEventTypes.RESPOND_MESSAGE_EVENT,
                Map.of("message", "final answer"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), leaf.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondMessage").value("final answer"))
                .andExpect(jsonPath("$.childRunId").doesNotExist());
    }

    @Test
    void processedCheckWithNoChildReportsNotPending() throws Exception {
        Project project = projectService.createProject("Chain read B4");
        AgentRun parent = agentRunService.create(project.id(), project.activeRouteId(),
                AgentRunTriggerType.DECISION_CYCLE, null, null, "DRAFT_QUESTION");
        agentRunService.complete(parent.id(), AgentRunStatus.COMPLETED, "done");
        checkRepository.request(parent.id());
        long generation = checkRepository.currentGeneration(parent.id());
        checkRepository.markProcessed(parent.id(), generation);

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), parent.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continuationPending").value(false))
                .andExpect(jsonPath("$.respondMessage").doesNotExist());

        // A run with no RESPOND_MESSAGE and no linkage still exposes nulls.
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), parent.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childRunId").doesNotExist());
    }
}
