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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and claims durable decision-cycle runs. Stage A has no product
 * command surface yet: runs are enqueued explicitly (tests, operators), the
 * background worker claims them atomically, and progress is recorded as
 * append-only run events.
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
     * Fails fast when the project or its active route does not exist.
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

    /** Atomically claims the next queued run, if any. */
    public Optional<AgentRun> claimNext() {
        return agentRunRepository.claimNextDecisionCycleRun();
    }

    public Optional<AgentRun> getRun(UUID runId) {
        return agentRunService.getRun(runId);
    }
}
