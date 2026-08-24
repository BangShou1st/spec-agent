package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunRequestFingerprint;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and claims durable runs. Supports both legacy DECISION_CYCLE runs
 * (Stage A background worker) and ANSWER_CYCLE runs (Stage B async command
 * surface). The worker dispatches by trigger type.
 */
@Service
public class RunService {

    private final AgentRunService agentRunService;
    private final AgentRunRepository agentRunRepository;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final AgentRunEventService eventService;

    public RunService(AgentRunService agentRunService,
                             AgentRunRepository agentRunRepository,
                             ProjectRepository projectRepository,
                             RouteRepository routeRepository,
                             AgentRunEventService eventService) {
        this.agentRunService = agentRunService;
        this.agentRunRepository = agentRunRepository;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.eventService = eventService;
    }

    /**
     * Enqueues one decision-cycle run against the project's active route.
     * Stage-A callers queued a bare decision cycle; the production operation
     * is now the explicit question draft ({@code DRAFT_QUESTION}).
     */
    public AgentRun createQueuedDraftQuestion(UUID projectId) {
        return createQueuedDraftQuestion(projectId, null);
    }

    /** Idempotent variant: retries with the same key return the same run. */
    public AgentRun createQueuedDraftQuestion(UUID projectId, String idempotencyKey) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        String fingerprint = AgentRunRequestFingerprint.forRequest(
                projectId, "DRAFT_QUESTION", route.id(), route.tipNodeId(),
                null, null, null, null);
        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.DECISION_CYCLE,
                route.tipNodeId(), null, "DRAFT_QUESTION", idempotencyKey, fingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.DECISION_CYCLE.code(),
                "operation", "DRAFT_QUESTION",
                "routeId", route.id().toString()));
        return run;
    }

    /**
     * Enqueues one answer-cycle run with operation input. The worker
     * dispatches to AnswerCycleService based on the trigger type.
     * Input parameters are persisted in the RUN_CREATED event payload.
     */
    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId) {
        return createQueuedRunWithInput(projectId, operation, nodeId,
                selectedOptionId, freeText, answerId, null);
    }

    /** Idempotent variant: retries with the same key return the same run. */
    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId,
                                         String idempotencyKey) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey).id();
    }

    /** Returns the persisted run so API callers can build replay responses from it. */
    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        UUID inputNodeId = nodeId != null ? nodeId : route.tipNodeId();
        String fingerprint = AgentRunRequestFingerprint.forRequest(
                projectId, operation, route.id(), inputNodeId, null, answerId,
                selectedOptionId, freeText);
        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.ANSWER_CYCLE,
                inputNodeId, null, operation, idempotencyKey, fingerprint);
        AgentRun run = created.run();

        // Persist input parameters in the run event payload for the worker
        // to reconstruct when executing the answer cycle.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.ANSWER_CYCLE.code());
        payload.put("operation", operation != null ? operation : "");
        payload.put("routeId", route.id().toString());
        if (selectedOptionId != null) {
            payload.put("selectedOptionId", selectedOptionId.toString());
        }
        if (freeText != null) {
            payload.put("freeText", freeText);
        }
        if (answerId != null) {
            payload.put("answerId", answerId.toString());
        }
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    /**
     * Enqueues one contextual node-query run ("ask AI about this node").
     * The route is explicit — never resolved from an active/first/latest
     * fallback — and the user question is persisted in the RUN_CREATED event
     * payload for the worker.
     */
    public UUID createQueuedNodeQuery(UUID projectId, UUID routeId, UUID nodeId, String question) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Route does not belong to project: " + routeId);
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Node query question must not be blank");
        }

        AgentRun run = agentRunService.create(
                projectId, routeId, AgentRunTriggerType.NODE_QUERY, nodeId, null, "NODE_QUERY");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.NODE_QUERY.code());
        payload.put("operation", "NODE_QUERY");
        payload.put("routeId", routeId.toString());
        payload.put("nodeId", nodeId.toString());
        payload.put("question", question);
        eventService.append(run.id(), AgentRunPhase.CREATED, "RUN_CREATED", payload);
        return run.id();
    }

    /**
     * Enqueues one artifact generation run (initially only spec snapshots)
     * against the project's active route. The caller pre-validates readable
     * state for precise API errors; the service re-checks at execution time.
     */
    public AgentRun createQueuedArtifactGeneration(UUID projectId) {
        return createQueuedArtifactGeneration(projectId, null);
    }

    /** Idempotent variant: retries with the same key return the same run. */
    public AgentRun createQueuedArtifactGeneration(UUID projectId, String idempotencyKey) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        String fingerprint = AgentRunRequestFingerprint.forRequest(
                projectId, "GENERATE_ARTIFACT", route.id(), route.tipNodeId(),
                null, null, null, null);
        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.GENERATE_SPEC,
                route.tipNodeId(), null, "GENERATE_ARTIFACT", idempotencyKey, fingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.GENERATE_SPEC.code(),
                "operation", "GENERATE_ARTIFACT",
                "routeId", route.id().toString()));
        return run;
    }

    /**
     * Enqueues one replacement run against an explicit source route and
     * target node. The route is never resolved from an active/first/latest
     * fallback; the user instruction rides in the RUN_CREATED payload.
     */
    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction) {
        return createQueuedRegenerate(projectId, sourceRouteId, targetNodeId, instruction, null);
    }

    /** Idempotent variant: retries with the same key return the same run. */
    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction,
                                           String idempotencyKey) {
        Route route = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + sourceRouteId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route does not belong to project: " + sourceRouteId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.REGENERATE_NODE.code());
        payload.put("operation", "REGENERATE_NODE");
        payload.put("routeId", sourceRouteId.toString());
        payload.put("nodeId", targetNodeId.toString());
        if (instruction != null && !instruction.isBlank()) {
            payload.put("freeText", instruction);
        }

        String normalizedInstruction = instruction != null && !instruction.isBlank()
                ? instruction : null;
        String fingerprint = AgentRunRequestFingerprint.forRequest(
                projectId, "REGENERATE_NODE", sourceRouteId, targetNodeId,
                sourceRouteId, null, null, normalizedInstruction);
        var created = agentRunService.createWithIdempotency(
                projectId, sourceRouteId, AgentRunTriggerType.REGENERATE_NODE,
                targetNodeId, null, "REGENERATE_NODE", idempotencyKey, fingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    /**
     * Appends RUN_CREATED only for a freshly inserted run. An idempotent
     * replay returns the already-persisted winner, which carries its own
     * original event — never write a second one.
     */
    private void appendRunCreatedIfInserted(AgentRunService.CreateResult created,
                                            Map<String, Object> payload) {
        if (created.inserted()) {
            eventService.append(created.run().id(), AgentRunPhase.CREATED,
                    "RUN_CREATED", payload);
        }
    }

    /**
     * Returns the project's active route ID. Used by the controller to
     * check answer existence before enqueuing.
     */
    public UUID getActiveRouteId(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        return project.activeRouteId();
    }

    /** Atomically claims the next queued run, if any. */
    public Optional<AgentRun> claimNext() {
        return agentRunRepository.claimNextDecisionCycleRun();
    }

    /** Atomically claims the next queued artifact-generation run. */
    public Optional<AgentRun> claimNextArtifact() {
        return agentRunRepository.claimNextArtifactRun();
    }

    /** Atomically claims the next queued replacement run. */
    public Optional<AgentRun> claimNextRegenerate() {
        return agentRunRepository.claimNextRegenerateRun();
    }

    /**
     * Atomically claims one specific queued decision-cycle run by id. Used by
     * the deterministic test driver so a fixture always executes the run it
     * enqueued, never an unrelated queued row.
     */
    public Optional<AgentRun> claimDecisionCycleRun(UUID runId) {
        return agentRunRepository.claimDecisionCycleRun(runId);
    }

    /**
     * Atomically claims the next answer-cycle run. Similar to claimNext
     * but filters on ANSWER_CYCLE trigger type.
     */
    public Optional<AgentRun> claimNextAnswerCycle() {
        return agentRunRepository.claimNextAnswerCycleRun();
    }

    /** Atomically claims the next node-query run. */
    public Optional<AgentRun> claimNextNodeQuery() {
        return agentRunRepository.claimNextNodeQueryRun();
    }

    public Optional<AgentRun> getRun(UUID runId) {
        return agentRunService.getRun(runId);
    }
}
