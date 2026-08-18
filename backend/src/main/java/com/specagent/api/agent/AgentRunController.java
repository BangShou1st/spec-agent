package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.api.common.ApiException;
import com.specagent.common.Json;
import com.specagent.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Safe operator read of agent runs.
 *
 * <p>Phase 6.1 is read-only: no endpoint starts, answers, or mutates agent
 * runs. Run/project ownership is verified so a run from project A can never be
 * read through project B. Only safe metadata and the sanitized trace-step list
 * are exposed.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final ProjectService projectService;
    private final Json json;

    public AgentRunController(AgentRunService agentRunService,
                              ProjectService projectService,
                              Json json) {
        this.agentRunService = agentRunService;
        this.projectService = projectService;
        this.json = json;
    }

    @GetMapping
    public List<AgentRunResponse> listRuns(@PathVariable UUID projectId) {
        projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return agentRunService.listByProject(projectId).stream()
                .map(run -> AgentRunResponse.from(run, traceSteps(run.trace())))
                .toList();
    }

    @GetMapping("/{runId}")
    public AgentRunResponse getRun(@PathVariable UUID projectId, @PathVariable UUID runId) {
        AgentRun run = agentRunService.getRun(runId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Agent run not found"));
        if (!run.projectId().equals(projectId)) {
            throw ApiException.notFound("RUN_NOT_FOUND", "Agent run not found");
        }
        return AgentRunResponse.from(run, traceSteps(run.trace()));
    }

    /**
     * Decodes the persisted trace into safe lifecycle step strings.
     *
     * <p>The trace is stored as a JSON string through a JSONB column, so the
     * read-back value is a JSON string literal (outer quotes, escaped
     * newlines). It is decoded back to plain newline-joined steps. The trace
     * intentionally contains diagnostic lifecycle steps only, never raw
     * provider payloads or secrets.
     */
    private List<String> traceSteps(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank() || "null".equals(rawTrace)) {
            return List.of();
        }
        String trace = rawTrace;
        if (rawTrace.startsWith("\"")) {
            trace = json.read(rawTrace, String.class);
        }
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trace.split("\n"))
                .map(String::trim)
                .filter(step -> !step.isEmpty())
                .toList();
    }
}