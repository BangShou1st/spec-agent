package com.specagent.api.agent;

import com.specagent.agent.AgentOrchestrator;
import com.specagent.agent.AnswerRunResult;
import com.specagent.agent.AgentRunResult;
import com.specagent.agent.SpecRunResult;
import com.specagent.api.common.ApiException;
import com.specagent.api.common.CommandExecution;
import com.specagent.api.spec.SpecSnapshotResponse;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Command composition for agent execution endpoints.
 *
 * <p>All agent commands go through the existing orchestrator; this service only
 * pre-validates readable state for precise API errors (404/409) and translates
 * expected runtime failures into safe API errors. It never calls the model
 * gateway, never builds a {@code ContextSnapshot}, and never persists runtime
 * records itself.
 */
@Service
public class AgentCommandService {

    private final AgentOrchestrator orchestrator;
    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;
    private final AnswerService answerService;
    private final AgentRunDtoMapper agentRunDtoMapper;

    public AgentCommandService(AgentOrchestrator orchestrator,
                               ProjectService projectService,
                               RouteService routeService,
                               NodeService nodeService,
                               AnswerService answerService,
                               AgentRunDtoMapper agentRunDtoMapper) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.agentRunDtoMapper = agentRunDtoMapper;
    }

    public DraftQuestionResponse draftNext(UUID projectId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireActiveRoute(project, routeService);
            AgentRunResult result = orchestrator.draftNextQuestion(projectId);
            return new DraftQuestionResponse(
                    agentRunDtoMapper.from(result.run()),
                    com.specagent.api.node.NodeResponse.from(result.producedNode()));
        });
    }

    public AnswerExecutionResponse submitAnswer(UUID projectId, SubmitAnswerRequest request) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            Route route = CommandExecution.requireActiveRoute(project, routeService);
            if (route.tipNodeId() == null) {
                throw ApiException.conflict("NO_ACTIVE_TIP_NODE",
                        "The active route has no tip node to answer");
            }
            if (answerService.existsAnswerFor(route.id(), route.tipNodeId())) {
                throw ApiException.conflict("ANSWER_ALREADY_FINALIZED",
                        "The active node has already been answered");
            }
            AnswerRunResult result = orchestrator.answerActiveNodeAndDraftNext(
                    projectId, request.selectedOptionId(), request.freeText());
            return AnswerExecutionResponse.from(agentRunDtoMapper.from(result.run()), result);
        });
    }

    public AnswerExecutionResponse repairAnswer(UUID projectId, UUID answerId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            Answer answer = CommandExecution.requireAnswerInProject(
                    projectService, answerService, projectId, answerId);
            Route route = CommandExecution.requireActiveRoute(project, routeService);
            if (!answer.routeId().equals(route.id())
                    || !answer.nodeId().equals(route.tipNodeId())) {
                throw ApiException.conflict("ANSWER_NOT_IN_ACTIVE_FLOW",
                        "The answer is not part of the active flow");
            }
            AnswerRunResult result = orchestrator.repairAnswerProcessingAndDraftNext(projectId, answerId);
            return AnswerExecutionResponse.from(agentRunDtoMapper.from(result.run()), result);
        });
    }

    public SpecGenerationResponse generateSpec(UUID projectId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            Route route = CommandExecution.requireActiveRoute(project, routeService);
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
