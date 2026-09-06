package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunRequestFingerprint;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.loop.LoopLinkage;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, null).id();
    }

    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId,
                                         AgentEvent.PersistenceIntent persistenceIntent) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, null, persistenceIntent).id();
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
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, null, null);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   AgentEvent.PersistenceIntent persistenceIntent) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, null, persistenceIntent);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   String requestFingerprint) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, requestFingerprint, null);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   String requestFingerprint,
                                                   AgentEvent.PersistenceIntent persistenceIntent) {
        String fingerprint = requestFingerprint != null ? requestFingerprint
                : AgentRunRequestFingerprint.forClientRequest(
                projectId, operation, nodeId, null, answerId, selectedOptionId, freeText,
                persistenceIntent);
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
                inputNodeId, null, operation, idempotencyKey, fingerprint);
        AgentRun run = created.run();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.ANSWER_CYCLE.code());
        payload.put("operation", operation != null ? operation : "");
        payload.put("routeId", route.id().toString());
        if (selectedOptionId != null) payload.put("selectedOptionId", selectedOptionId.toString());
        if (freeText != null) payload.put("freeText", freeText);
        if (answerId != null) payload.put("answerId", answerId.toString());
        if (persistenceIntent != null) payload.put("persistenceIntent", persistenceIntent.name());
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    public UUID createQueuedNodeQuery(UUID projectId, UUID routeId, UUID nodeId, String question) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        // The route is OPTIONAL reading context: floating nodes (routeIds=[])
        // query with routeId=null and the anchor node as the sole context.
        if (routeId != null) {
            Route route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
            if (!route.projectId().equals(projectId)) {
                throw new IllegalArgumentException("Route does not belong to project: " + routeId);
            }
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Node query question must not be blank");
        }

        AgentRun run = agentRunService.create(
                projectId, routeId, AgentRunTriggerType.NODE_QUERY, nodeId, null, "NODE_QUERY");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.NODE_QUERY.code());
        payload.put("operation", "NODE_QUERY");
        payload.put("routeId", routeId == null ? null : routeId.toString());
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

    /**
     * Creates the autonomous continuation child of a terminal parent run.
     *
     * <p>Linkage rules: a chain root (no persisted root/cycle) mothers a
     * child with {@code rootRunId = parent.id} at cycle 1; deeper parents
     * keep their root and increment the cycle. The stale anchor reuses the
     * existing {@code inputNodeId} mechanism: the child records the parent
     * route's tip at creation time, and Slice 3 execution fails closed when
     * existing {@code inputNodeId} mechanism: the child records the
     * row-derived expected tip, and Slice 3 execution fails closed when
     * the live tip no longer equals it (same check as
     * {@code DecisionCycleService} draft targets, null-safe for empty
     * routes). No new anchor metadata is introduced.
     *
     * <p>Stale-anchor gate: the expected tip derives from the parent row
     * alone — the produced node when the parent moved the tip, otherwise
     * the input node it decided against (both null on an empty route).
     * The live tip must still equal it, or creation throws
     * {@link StaleRunTargetException} instead of letting an autonomous
     * continuation follow newer external causality. The rule reads Runtime
     * graph state and produced refs only; no action family participates.
     *
     * <p>Exactly-once: the deterministic key
     * {@code "continue:<parentRunId>"} plus the project-scoped idempotency
     * unique index arbitrate concurrent creators — duplicate calls return
     * the one persisted child, never a second row. The method takes the
     * parent row only: no action family, conflict, or other semantic input
     * participates in child identity.
     */
    public AgentRunService.CreateResult createContinueRun(AgentRun parent) {
        if (parent.routeId() == null) {
            throw new StaleRunTargetException(
                    "Parent run " + parent.id()
                            + " has no route to anchor a continuation; refusing");
        }
        Route route = routeRepository.findById(parent.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent route not found: " + parent.routeId()));
        if (!route.projectId().equals(parent.projectId())) {
            throw new IllegalArgumentException(
                    "Parent route does not belong to project: " + parent.routeId());
        }
        UUID expectedTip = parent.producedNodeId() != null
                ? parent.producedNodeId() : parent.inputNodeId();
        if (!Objects.equals(route.tipNodeId(), expectedTip)) {
            throw new StaleRunTargetException(
                    "Parent route tip moved since parent " + parent.id()
                            + " completed: expected " + expectedTip
                            + " but live tip is " + route.tipNodeId()
                            + "; refusing autonomous continuation");
        }
        UUID rootId = parent.rootRunId() != null ? parent.rootRunId() : parent.id();
        int parentCycle = parent.cycleIndex() != null ? parent.cycleIndex() : 0;
        int childCycle = parentCycle + 1;
        String key = "continue:" + parent.id();
        String fingerprint = AgentRunRequestFingerprint.forContinuation(
                parent.projectId(), parent.id(), childCycle);

        var created = agentRunService.createWithIdempotency(
                parent.projectId(), route.id(), AgentRunTriggerType.CONTINUE_CYCLE,
                expectedTip, null, "CONTINUE", key, fingerprint,
                new LoopLinkage(parent.id(), rootId, childCycle));
        AgentRun run = created.run();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.CONTINUE_CYCLE.code());
        payload.put("operation", "CONTINUE");
        payload.put("routeId", route.id().toString());
        payload.put("parentRunId", parent.id().toString());
        payload.put("rootRunId", rootId.toString());
        payload.put("cycleIndex", childCycle);
        appendRunCreatedIfInserted(created, payload);
        return created;
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
    public Optional<AgentRun> claimNodeQueryRun(UUID runId) { return agentRunRepository.claimNodeQueryRun(runId); }
    public Optional<AgentRun> claimNextContinue() { return agentRunRepository.claimNextContinueRun(); }
    public Optional<AgentRun> getRun(UUID runId) { return agentRunService.getRun(runId); }
}
