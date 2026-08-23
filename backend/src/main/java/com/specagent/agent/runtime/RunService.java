package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
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
     */
    public AgentRun createQueuedRun(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        AgentRun run = agentRunService.create(
                projectId, route.id(), AgentRunTriggerType.DECISION_CYCLE,
                route.tipNodeId(), null);
        eventService.append(run.id(), AgentRunPhase.CREATED, "RUN_CREATED", Map.of(
                "triggerType", AgentRunTriggerType.DECISION_CYCLE.code(),
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
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));

        UUID inputNodeId = nodeId != null ? nodeId : route.tipNodeId();
        AgentRun run = agentRunService.create(
                projectId, route.id(), AgentRunTriggerType.ANSWER_CYCLE,
                inputNodeId, null, operation);

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
        eventService.append(run.id(), AgentRunPhase.CREATED, "RUN_CREATED", payload);
        return run.id();
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
