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

/** Creates, claims, and reads durable agent runs. */
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

    public AgentRun createQueuedDraftQuestion(UUID projectId) {
        return createQueuedDraftQuestion(projectId, null);
    }

    public AgentRun createQueuedDraftQuestion(UUID projectId, String idempotencyKey) {
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "DRAFT_QUESTION", null, null, null, null, null);
        return createQueuedDraftQuestion(projectId, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedDraftQuestion(UUID projectId,
                                              String idempotencyKey,
                                              String requestFingerprint) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.DECISION_CYCLE,
                route.tipNodeId(), null, "DRAFT_QUESTION", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.DECISION_CYCLE.code(),
                "operation", "DRAFT_QUESTION",
                "routeId", route.id().toString()));
        return run;
    }

    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId) {
        return createQueuedRunWithInput(projectId, operation, nodeId,
                selectedOptionId, freeText, answerId, null);
    }

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

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey) {
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, operation, nodeId, null, answerId, selectedOptionId, freeText);
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   String requestFingerprint) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        UUID inputNodeId = nodeId != null ? nodeId : route.tipNodeId();
        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.ANSWER_CYCLE,
                inputNodeId, null, operation, idempotencyKey, requestFingerprint);
        AgentRun run = created.run();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.ANSWER_CYCLE.code());
        payload.put("operation", operation != null ? operation : "");
        payload.put("routeId", route.id().toString());
        if (selectedOptionId != null) payload.put("selectedOptionId", selectedOptionId.toString());
        if (freeText != null) payload.put("freeText", freeText);
        if (answerId != null) payload.put("answerId", answerId.toString());
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

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

    public AgentRun createQueuedArtifactGeneration(UUID projectId) {
        return createQueuedArtifactGeneration(projectId, null);
    }

    public AgentRun createQueuedArtifactGeneration(UUID projectId, String idempotencyKey) {
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "GENERATE_ARTIFACT", null, null, null, null, null);
        return createQueuedArtifactGeneration(projectId, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedArtifactGeneration(UUID projectId,
                                                   String idempotencyKey,
                                                   String requestFingerprint) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.GENERATE_SPEC,
                route.tipNodeId(), null, "GENERATE_ARTIFACT", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.GENERATE_SPEC.code(),
                "operation", "GENERATE_ARTIFACT",
                "routeId", route.id().toString()));
        return run;
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction) {
        return createQueuedRegenerate(projectId, sourceRouteId, targetNodeId, instruction, null);
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction,
                                           String idempotencyKey) {
        String normalizedInstruction = instruction != null && !instruction.isBlank()
                ? instruction : null;
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "REGENERATE_NODE", targetNodeId, sourceRouteId,
                null, null, normalizedInstruction);
        return createQueuedRegenerate(projectId, sourceRouteId, targetNodeId,
                instruction, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction,
                                           String idempotencyKey,
                                           String requestFingerprint) {
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
        if (instruction != null && !instruction.isBlank()) payload.put("freeText", instruction);

        var created = agentRunService.createWithIdempotency(
                projectId, sourceRouteId, AgentRunTriggerType.REGENERATE_NODE,
                targetNodeId, null, "REGENERATE_NODE", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    private void appendRunCreatedIfInserted(AgentRunService.CreateResult created,
                                            Map<String, Object> payload) {
        if (created.inserted()) {
            eventService.append(created.run().id(), AgentRunPhase.CREATED,
                    "RUN_CREATED", payload);
        }
    }

    public UUID getActiveRouteId(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        return project.activeRouteId();
    }

    public Optional<AgentRun> claimNext() { return agentRunRepository.claimNextDecisionCycleRun(); }
    public Optional<AgentRun> claimNextArtifact() { return agentRunRepository.claimNextArtifactRun(); }
    public Optional<AgentRun> claimNextRegenerate() { return agentRunRepository.claimNextRegenerateRun(); }
    public Optional<AgentRun> claimDecisionCycleRun(UUID runId) { return agentRunRepository.claimDecisionCycleRun(runId); }
    public Optional<AgentRun> claimNextAnswerCycle() { return agentRunRepository.claimNextAnswerCycleRun(); }
    public Optional<AgentRun> claimNextNodeQuery() { return agentRunRepository.claimNextNodeQueryRun(); }
    public Optional<AgentRun> getRun(UUID runId) { return agentRunService.getRun(runId); }
}
