package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runtime.RunService;
import com.specagent.answer.AnswerService;
import com.specagent.route.RouteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Async answer-cycle command + run read surface.
 *
 * <p>POST enqueues an ANSWER_CYCLE run (202 + runId) with an explicit
 * operation ({@code ANSWER_TIP} for a fresh answer, {@code RESUME_ANSWER} to
 * resume the persisted Answer of a failed cycle — never a second Answer).
 * The background worker executes the 2-call convergence; clients poll
 * {@code GET .../agent-runs/{runId}} until a terminal status. The read view
 * exposes the real persisted phase (latest run event) and the produced record
 * ids so callers can reconcile without guessing outcomes.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AnswerCycleRunController {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final AnswerService answerService;
    private final RouteService routeService;

    public AnswerCycleRunController(RunService runService,
                                    AgentRunService agentRunService,
                                    AgentRunEventService eventService,
                                    AnswerService answerService,
                                    RouteService routeService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.answerService = answerService;
        this.routeService = routeService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRun(
            @PathVariable UUID projectId,
            @RequestBody CreateRunRequest request) {

        // Determine operation: if answer already exists for this node+route,
        // route to RESUME_ANSWER when the answer's cycle is still unfinished
        // (the node is still the active tip). When the tip has already moved
        // past the answered node, the original cycle completed — reject the
        // duplicate synchronously instead of enqueueing a doomed run.
        String operation = request.operation();
        UUID answerId = request.answerId();

        if ("ANSWER_TIP".equals(operation) && request.nodeId() != null && answerId == null) {
            UUID activeRouteId = runService.getActiveRouteId(projectId);
            boolean answerExists = answerService.existsAnswerFor(activeRouteId, request.nodeId());
            if (answerExists) {
                UUID tipNodeId = routeService.getRoute(activeRouteId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Active route not found: " + activeRouteId))
                        .tipNodeId();
                if (!request.nodeId().equals(tipNodeId)) {
                    throw com.specagent.api.common.ApiException.conflict(
                            "ANSWER_ALREADY_FINALIZED",
                            "The active node has already been answered");
                }
                operation = "RESUME_ANSWER";
                answerId = answerService.findAnswerForNode(activeRouteId, request.nodeId())
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
    public ResponseEntity<?> getRun(
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return agentRunService.getRun(runId)
                .filter(run -> run.projectId().equals(projectId))
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(runView(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> runView(AgentRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.id().toString());
        view.put("projectId", run.projectId().toString());
        view.put("routeId", run.routeId().toString());
        view.put("operation", run.operation() != null ? run.operation() : "");
        // status is the coarse lifecycle state; phase is the latest persisted
        // run event so progress copy always derives from real runtime phases.
        view.put("status", run.status().code());
        view.put("phase", latestPhaseCode(run.id()));
        view.put("producedNodeId", toStringOrNull(run.producedNodeId()));
        view.put("producedAnswerId", toStringOrNull(run.producedAnswerId()));
        view.put("producedPatchId", toStringOrNull(run.producedPatchId()));
        view.put("producedSpecSnapshotId", toStringOrNull(run.producedSpecSnapshotId()));
        return view;
    }

    /** Latest persisted event phase; CREATED before any event exists. */
    private String latestPhaseCode(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .reduce((first, second) -> second)
                .map(event -> event.phase().code())
                .orElse(AgentRunPhase.CREATED.code());
    }

    private String toStringOrNull(UUID value) {
        return value == null ? null : value.toString();
    }

    public record CreateRunRequest(String operation,
                                   UUID nodeId,
                                   UUID selectedOptionId,
                                   String freeText,
                                   UUID answerId) {
    }
}
