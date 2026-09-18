package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRequestFingerprint;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.contract.AgentEvent;
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

/** Async agent-run command + polling API. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AnswerCycleRunController {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final AnswerService answerService;
    private final RouteService routeService;
    private final com.specagent.project.ProjectService projectService;
    private final com.specagent.node.NodeService nodeService;
    private final com.specagent.agent.loop.AgentRunChainReadService chainReadService;

    public AnswerCycleRunController(RunService runService,
                                    AgentRunService agentRunService,
                                    AgentRunEventService eventService,
                                    AnswerService answerService,
                                    RouteService routeService,
                                    com.specagent.project.ProjectService projectService,
                                    com.specagent.node.NodeService nodeService,
                                    com.specagent.agent.loop.AgentRunChainReadService chainReadService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.answerService = answerService;
        this.routeService = routeService;
        this.projectService = projectService;
        this.nodeService = nodeService;
        this.chainReadService = chainReadService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRun(
            @PathVariable UUID projectId,
            @RequestBody CreateRunRequest request) {
        return com.specagent.api.common.CommandExecution.execute(
                () -> createRunInternal(projectId, request));
    }

    private ResponseEntity<Map<String, Object>> createRunInternal(
            UUID projectId, CreateRunRequest request) {

        String operation = request.operation();
        String idempotencyKey = request.idempotencyKey();
        String requestFingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, operation, request.nodeId(), request.sourceRouteId(),
                request.answerId(), request.selectedOptionId(), request.freeText(),
                request.persistenceIntent());

        var replay = agentRunService.findIdempotentReplay(
                projectId, idempotencyKey, requestFingerprint);
        if (replay.isPresent()) {
            return acceptedRun(replay.get());
        }

        // 可选显式路线：本次 run 从此只认这条路线，而不是项目唯一的 Active 指针。
        // 这是"多条路线各自独立生成/回答"的入口 —— 路由归属由端点校验（同项目 + OPEN
        // 在 RunService 里做），未提供时下面每一条分支都退回原有的 Active 语义。
        UUID explicitRouteId = request.sourceRouteId();
        if (explicitRouteId != null) {
            com.specagent.api.common.CommandExecution.requireRouteInProject(
                    projectService, routeService, projectId, explicitRouteId);
        }

        if ("DRAFT_QUESTION".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
            return acceptedRun(runService.createQueuedDraftQuestion(
                    projectId, idempotencyKey, requestFingerprint, explicitRouteId));
        }

        if ("GENERATE_ARTIFACT".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
            UUID targetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            var route = routeService.getRoute(targetRouteId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Route not found: " + targetRouteId));
            if (route.tipNodeId() == null) {
                throw com.specagent.api.common.ApiException.conflict(
                        "NO_ACTIVE_TIP_NODE",
                        "The active route has no tip node to generate a spec from");
            }
            return acceptedRun(runService.createQueuedArtifactGeneration(
                    projectId, idempotencyKey, requestFingerprint, explicitRouteId));
        }

        if ("REGENERATE_NODE".equals(operation)) {
            if (request.nodeId() == null || request.sourceRouteId() == null) {
                throw com.specagent.api.common.ApiException.badRequest(
                        "REGENERATE_TARGET_REQUIRED",
                        "Replacement requires an explicit source route and node");
            }
            com.specagent.api.common.CommandExecution.requireProject(projectService, projectId);
            var target = com.specagent.api.common.CommandExecution.requireNodeInProject(
                    projectService, nodeService, projectId, request.nodeId());
            if (target.parentNodeId() == null) {
                throw com.specagent.api.common.ApiException.conflict(
                        "REGENERATE_ROOT_NOT_SUPPORTED",
                        "Root node regeneration is not supported");
            }
            return acceptedRun(runService.createQueuedRegenerate(
                    projectId, request.sourceRouteId(), request.nodeId(),
                    request.freeText(), idempotencyKey, requestFingerprint));
        }

        if ("ANSWER_TIP".equals(operation) || "RESUME_ANSWER".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
        }

        UUID answerId = request.answerId();
        if ("ANSWER_TIP".equals(operation) && request.nodeId() != null && answerId == null) {
            UUID targetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            boolean answerExists = answerService.existsAnswerFor(targetRouteId, request.nodeId());
            if (answerExists) {
                UUID tipNodeId = routeService.getRoute(targetRouteId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Route not found: " + targetRouteId))
                        .tipNodeId();
                if (!request.nodeId().equals(tipNodeId)) {
                    throw com.specagent.api.common.ApiException.conflict(
                            "ANSWER_ALREADY_FINALIZED",
                            "The active node has already been answered");
                }
                operation = "RESUME_ANSWER";
                answerId = answerService.findAnswerForNode(targetRouteId, request.nodeId())
                        .map(a -> a.id()).orElse(null);
            }
        }

        AgentRun run = runService.createQueuedRunWithInputResultForRoute(
                projectId, operation, request.nodeId(),
                request.selectedOptionId(), request.freeText(), answerId,
                idempotencyKey, requestFingerprint, request.persistenceIntent(), explicitRouteId);
        return acceptedRun(run);
    }

    private void requireActiveRoute(UUID projectId) {
        com.specagent.api.common.CommandExecution.requireActiveRoute(
                com.specagent.api.common.CommandExecution.requireProject(projectService, projectId),
                routeService);
    }

    private ResponseEntity<Map<String, Object>> acceptedRun(AgentRun run) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", run.id().toString());
        response.put("operation", run.operation() == null ? "" : run.operation());
        response.put("status", run.status().code());
        response.put("phase", latestPhaseCode(run.id()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> getRun(@PathVariable UUID projectId,
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
        view.put("status", run.status().code());
        view.put("phase", latestPhaseCode(run.id()));
        view.put("producedNodeId", toStringOrNull(run.producedNodeId()));
        view.put("producedAnswerId", toStringOrNull(run.producedAnswerId()));
        view.put("producedPatchId", toStringOrNull(run.producedPatchId()));
        view.put("producedSpecSnapshotId", toStringOrNull(run.producedSpecSnapshotId()));
        var chain = chainReadService.read(run.id());
        view.put("childRunId", chain.childRunId() == null ? null : chain.childRunId().toString());
        view.put("continuationPending", chain.continuationPending());
        view.put("respondMessage", chain.respondMessage());
        return view;
    }

    private String latestPhaseCode(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .reduce((first, second) -> second)
                .map(event -> event.phase().code())
                .orElse(AgentRunPhase.CREATED.code());
    }

    private String toStringOrNull(UUID value) { return value == null ? null : value.toString(); }

    public record CreateRunRequest(String operation,
                                   UUID nodeId,
                                   UUID sourceRouteId,
                                   UUID selectedOptionId,
                                   String freeText,
                                   UUID answerId,
                                   String idempotencyKey,
                                   AgentEvent.PersistenceIntent persistenceIntent) {
    }
}
