package com.specagent.api.agent;

import com.specagent.application.agent.AcceptedRunView;
import com.specagent.application.agent.AnswerCycleRunCommandService;
import com.specagent.application.agent.CreateRunRequest;
import com.specagent.application.support.CommandExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Async agent-run command + polling API. Orchestration lives in
 * {@link AnswerCycleRunCommandService}; this surface is the HTTP contract. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AnswerCycleRunController {

    private final AnswerCycleRunCommandService commandService;
    private final com.specagent.agent.AgentRunService agentRunService;

    public AnswerCycleRunController(AnswerCycleRunCommandService commandService,
                                    com.specagent.agent.AgentRunService agentRunService) {
        this.commandService = commandService;
        this.agentRunService = agentRunService;
    }

    @PostMapping
    public ResponseEntity<AcceptedRunView> createRun(
            @PathVariable UUID projectId,
            @RequestBody CreateRunRequest request) {
        return CommandExecution.execute(() -> commandService.createRun(projectId, request));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> getRun(@PathVariable UUID projectId,
                                    @PathVariable UUID runId) {
        return agentRunService.getRun(runId)
                .filter(run -> run.projectId().equals(projectId))
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(commandService.runView(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** All non-terminal runs of the project; lets the frontend rebuild its
     * in-flight run registry after a page reload. */
    @GetMapping("/active")
    public ResponseEntity<?> listActiveRuns(@PathVariable UUID projectId) {
        return ResponseEntity.ok(agentRunService.listActiveByProject(projectId).stream()
                .map(commandService::runView)
                .toList());
    }
}
