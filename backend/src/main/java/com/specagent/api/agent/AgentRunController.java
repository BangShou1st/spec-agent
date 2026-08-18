package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.api.common.ApiException;
import com.specagent.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Safe operator read of agent runs.
 *
 * <p>Phase 6.1 read contract preserved: no endpoint starts, answers, or mutates
 * agent runs. Run/project ownership is verified so a run from project A can
 * never be read through project B. Only safe metadata and the sanitized
 * trace-step list are exposed.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final ProjectService projectService;
    private final AgentRunDtoMapper agentRunDtoMapper;

    public AgentRunController(AgentRunService agentRunService,
                              ProjectService projectService,
                              AgentRunDtoMapper agentRunDtoMapper) {
        this.agentRunService = agentRunService;
        this.projectService = projectService;
        this.agentRunDtoMapper = agentRunDtoMapper;
    }

    @GetMapping
    public List<AgentRunResponse> listRuns(@PathVariable UUID projectId) {
        projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return agentRunService.listByProject(projectId).stream()
                .map(agentRunDtoMapper::from)
                .toList();
    }

    @GetMapping("/{runId}")
    public AgentRunResponse getRun(@PathVariable UUID projectId, @PathVariable UUID runId) {
        AgentRun run = agentRunService.getRun(runId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Agent run not found"));
        if (!run.projectId().equals(projectId)) {
            throw ApiException.notFound("RUN_NOT_FOUND", "Agent run not found");
        }
        return agentRunDtoMapper.from(run);
    }
}