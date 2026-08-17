package com.specagent.agent;

import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.gates.NodeReflectionGate;
import com.specagent.common.Json;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Runtime-controlled fake agent orchestrator.
 *
 * <p>Runs one minimal DRAFT_NODE cycle against the fake model adapter: create
 * an agent run, freeze a context snapshot, ask the fake model for a node draft,
 * validate the draft through the reflection gates, persist the node, and close
 * the run. The orchestrator only proposes through the model adapter; all
 * persistence happens through runtime services. This is not a full answer
 * loop: it never interprets answers, creates answer patches, or generates spec
 * snapshots.
 *
 * <p>The cycle is not wrapped in a single transaction: each runtime step
 * persists on its own, and unexpected failures are recorded through
 * {@link AgentRunFailureService} in a separate transaction so the FAILED run
 * stays queryable after the exception is rethrown.
 */
@Service
public class FakeAgentOrchestrator {

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final FakeModelAdapter fakeModelAdapter;
    private final NodeReflectionGate nodeReflectionGate;
    private final NodeService nodeService;
    private final Json json;

    public FakeAgentOrchestrator(AgentRunService agentRunService,
                                 AgentRunFailureService agentRunFailureService,
                                 ProjectRepository projectRepository,
                                 RouteRepository routeRepository,
                                 ContextBuilder contextBuilder,
                                 ContextGuard contextGuard,
                                 FakeModelAdapter fakeModelAdapter,
                                 NodeReflectionGate nodeReflectionGate,
                                 NodeService nodeService,
                                 Json json) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.fakeModelAdapter = fakeModelAdapter;
        this.nodeReflectionGate = nodeReflectionGate;
        this.nodeService = nodeService;
        this.json = json;
    }

    public FakeAgentRunResult draftNextQuestion(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }

        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + project.activeRouteId()));

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.INITIAL_REQUIREMENT,
                route.tipNodeId(),
                null);

        try {
            ContextSnapshot contextSnapshot = contextBuilder.buildFromActiveRoute(
                    projectId,
                    run.id(),
                    ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), contextSnapshot.id(), "context_built");

            ReflectionResult contextReflection = contextGuard.validate(contextSnapshot);
            if (!contextReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(contextReflection));
                throw new ModelContractException("Context guard rejected fake agent run");
            }

            ModelRequest request = new ModelRequest(
                    projectId,
                    route.id(),
                    run.id(),
                    contextSnapshot.id(),
                    AgentTaskType.DRAFT_NODE,
                    "{}",
                    Map.of("orchestrator", "fake"));

            ModelResponse response = fakeModelAdapter.run(request);
            agentRunService.markModelCalled(run.id(), json.write(response.trace()));

            if (response.action() != AgentAction.ASK_NEXT_QUESTION) {
                agentRunService.fail(run.id(), json.write(response));
                throw new ModelContractException("Expected ASK_NEXT_QUESTION from fake DRAFT_NODE");
            }

            NodeDraft draft = json.read(response.outputJson(), NodeDraft.class);
            ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
            agentRunService.markReflected(run.id(), json.write(nodeReflection));

            if (!nodeReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(nodeReflection));
                throw new ModelContractException("Node reflection rejected fake node draft");
            }

            Node producedNode;
            if (route.tipNodeId() == null) {
                producedNode = nodeService.createRootNode(
                        projectId,
                        route.id(),
                        draft.question(),
                        draft.purpose(),
                        draft.options(),
                        draft.allowFreeAnswer());
            } else {
                producedNode = nodeService.createChildNode(
                        projectId,
                        route.id(),
                        route.tipNodeId(),
                        draft.question(),
                        draft.purpose(),
                        draft.options(),
                        draft.allowFreeAnswer());
            }

            agentRunService.markPersistedNode(run.id(), producedNode.id(), "produced_node");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "completed");

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new FakeAgentRunResult(completedRun, contextSnapshot, response, producedNode);
        } catch (RuntimeException ex) {
            AgentRun latest = agentRunService.getRun(run.id()).orElse(null);
            if (latest != null && latest.status() != AgentRunStatus.FAILED
                    && latest.status() != AgentRunStatus.COMPLETED) {
                // Record the failure in its own transaction so the FAILED run
                // survives the rethrow instead of being rolled back.
                agentRunFailureService.fail(run.id(), "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}");
            }
            throw ex;
        }
    }
}