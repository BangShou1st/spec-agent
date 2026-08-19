package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.model.gateway.ModelGateway;
import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.gateway.ModelGatewayException;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ModelPoweredReplacementIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private AgentOrchestrator orchestrator;
    @Autowired
    private ModelContextProjectionBuilder projectionBuilder;

    @MockBean
    private ModelGateway modelGateway;

    @Test
    void replacementCommitsSiblingOnlyAfterAcceptedDraft() {
        Scenario scenario = scenario();
        when(modelGateway.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(request.agentRunId(), request.contextSnapshotId(),
                    request.taskType(), AgentAction.ASK_NEXT_QUESTION,
                    "{\"question\":\"Which outcome should be prioritized first?\","
                            + "\"purpose\":\"Clarify the next decision.\","
                            + "\"options\":[],\"allowFreeAnswer\":true}",
                    Map.of("adapter", "scripted"));
        });

        ReplacementRunResult result = orchestrator.replaceQuestion(
                scenario.project.id(), scenario.route.id(), scenario.target.id(), "先澄清优先级");

        Route oldRoute = routeRepository.findById(scenario.route.id()).orElseThrow();
        Route newRoute = routeRepository.findById(result.replacement().replacementRoute().id()).orElseThrow();
        Node newNode = result.replacement().replacementNode();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(newRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(newRoute.tipNodeId()).isEqualTo(newNode.id());
        assertThat(newNode.parentNodeId()).isEqualTo(scenario.parent.id());
        assertThat(newNode.supersedesNodeId()).isEqualTo(scenario.target.id());
        assertThat(projectService.getProject(scenario.project.id()).orElseThrow().activeRouteId())
                .isEqualTo(newRoute.id());

        ContextSnapshot context = result.contextSnapshot();
        assertThat(context.routeId()).isEqualTo(scenario.route.id());
        assertThat(context.tipNodeId()).isEqualTo(scenario.parent.id());
        assertThat(context.includedNodeIds()).containsExactly(scenario.parent.id());
        assertThat(context.includedNodeIds()).doesNotContain(scenario.target.id());
        String input = projectionBuilder.buildInputJson(context,
                projectionBuilder.redirectedNodeTaskInput("先澄清优先级"));
        assertThat(input).contains("先澄清优先级", "Which outcome should be prioritized?")
                .doesNotContain(scenario.target.id().toString(), newRoute.id().toString(), newNode.id().toString());
    }

    @Test
    void providerFailureLeavesCanonicalReplacementStateUntouched() {
        Scenario scenario = scenario();
        doThrow(new ModelGatewayException(ModelGatewayErrorCategory.CONNECTION,
                "provider unavailable")).when(modelGateway).run(any(ModelRequest.class));
        int nodeCount = nodeRepository.findByProject(scenario.project.id()).size();
        int routeCount = routeRepository.findByProject(scenario.project.id()).size();

        assertThatThrownBy(() -> orchestrator.replaceQuestion(
                scenario.project.id(), scenario.route.id(), scenario.target.id(), "换一个澄清方向"))
                .isInstanceOf(ModelGatewayException.class);

        assertThat(nodeRepository.findByProject(scenario.project.id())).hasSize(nodeCount);
        assertThat(routeRepository.findByProject(scenario.project.id())).hasSize(routeCount);
        assertThat(routeRepository.findById(scenario.route.id()).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(projectService.getProject(scenario.project.id()).orElseThrow().activeRouteId())
                .isEqualTo(scenario.route.id());
    }

    private Scenario scenario() {
        Project project = projectService.createProject("replacement isolation " + System.nanoTime());
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node parent = nodeService.createRootNode(project.id(), route.id(),
                "What should be clarified first?", "Establish the context.", List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), parent.id(),
                "Which outcome should be prioritized?", "Clarify the rejected question.", List.of(), true);
        return new Scenario(project, routeRepository.findById(route.id()).orElseThrow(), parent, target);
    }

    private record Scenario(Project project, Route route, Node parent, Node target) {
    }
}
