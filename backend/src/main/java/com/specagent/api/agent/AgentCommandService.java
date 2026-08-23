package com.specagent.api.agent;

import com.specagent.agent.AgentOrchestrator;
import com.specagent.agent.AgentRunResult;
import com.specagent.agent.SpecRunResult;
import com.specagent.api.common.ApiException;
import com.specagent.api.common.CommandExecution;
import com.specagent.api.node.NodeResponse;
import com.specagent.api.spec.SpecSnapshotResponse;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Command composition for the remaining legacy agent execution endpoints
 * (draft question, spec generation). Answer/repair moved to the async Agent
 * Runtime ({@link AnswerCycleRunController}).
 *
 * <p>This service only pre-validates readable state for precise API errors
 * (404/409) and translates expected runtime failures into safe API errors.
 */
@Service
public class AgentCommandService {

    private final AgentOrchestrator orchestrator;
    private final ProjectService projectService;
    private final RouteService routeService;
    private final AgentRunDtoMapper agentRunDtoMapper;

    public AgentCommandService(AgentOrchestrator orchestrator,
                               ProjectService projectService,
                               RouteService routeService,
                               AgentRunDtoMapper agentRunDtoMapper) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
        this.routeService = routeService;
        this.agentRunDtoMapper = agentRunDtoMapper;
    }

    public DraftQuestionResponse draftNext(UUID projectId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireActiveRoute(project, routeService);
            AgentRunResult result = orchestrator.draftNextQuestion(projectId);
            return new DraftQuestionResponse(
                    agentRunDtoMapper.from(result.run()),
                    NodeResponse.from(result.producedNode()));
        });
    }

    public SpecGenerationResponse generateSpec(UUID projectId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            com.specagent.route.Route route = CommandExecution.requireActiveRoute(project, routeService);
            if (route.tipNodeId() == null) {
                throw ApiException.conflict("NO_ACTIVE_TIP_NODE",
                        "The active route has no tip node to generate a spec from");
            }
            SpecRunResult result = orchestrator.generateSpec(projectId);
            return new SpecGenerationResponse(
                    agentRunDtoMapper.from(result.run()),
                    SpecSnapshotResponse.from(result.specSnapshot()));
        });
    }
}
