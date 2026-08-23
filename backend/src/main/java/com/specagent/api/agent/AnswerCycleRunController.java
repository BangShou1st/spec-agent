package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.runtime.AnswerCycleService;
import com.specagent.agent.runtime.RunService;
import com.specagent.answer.AnswerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AnswerCycleRunController {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AnswerService answerService;

    public AnswerCycleRunController(RunService runService,
                                    AgentRunService agentRunService,
                                    AnswerService answerService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.answerService = answerService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRun(
            @PathVariable UUID projectId,
            @RequestBody CreateRunRequest request) {

        // Determine operation: if answer already exists for this node+route,
        // route to RESUME_ANSWER to avoid creating a second Answer.
        String operation = request.operation();
        UUID answerId = request.answerId();

        if ("ANSWER_TIP".equals(operation) && request.nodeId() != null && answerId == null) {
            boolean answerExists = answerService.existsAnswerFor(
                    runService.getActiveRouteId(projectId), request.nodeId());
            if (answerExists) {
                operation = "RESUME_ANSWER";
                answerId = answerService.findAnswerForNode(
                        runService.getActiveRouteId(projectId), request.nodeId())
                        .map(a -> a.id()).orElse(null);
            }
        }

        UUID runId = runService.createQueuedRunWithInput(
                projectId, operation, request.nodeId(),
                request.selectedOptionId(), request.freeText(), answerId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "runId", runId.toString(),
                "operation", operation,
                "phase", "CREATED"));
    }

    @GetMapping("/{runId}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> getRun(
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return agentRunService.getRun(runId)
                .filter(run -> run.projectId().equals(projectId))
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(Map.of(
                        "runId", run.id().toString(),
                        "status", run.status().code(),
                        "phase", run.status().code(),
                        "operation", run.operation() != null ? run.operation() : "",
                        "producedNodeId", run.producedNodeId() != null
                                ? run.producedNodeId().toString() : null)))
                .orElse(ResponseEntity.notFound().build());
    }

    public record CreateRunRequest(String operation,
                                   UUID nodeId,
                                   UUID selectedOptionId,
                                   String freeText,
                                   UUID answerId) {
    }
}
