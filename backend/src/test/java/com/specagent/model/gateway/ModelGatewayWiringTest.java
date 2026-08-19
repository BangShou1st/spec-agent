package com.specagent.model.gateway;

import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeModelAdapter;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.support.OpenCodeSettingsCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Gateway selection guarantees: exactly one {@link ModelGateway} is active per
 * runtime context, chosen by the explicit {@code spec.agent.model.gateway}
 * property. The default is the deterministic fake, so automated tests never
 * touch the public OpenCode API; {@code opencode} must be selected explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelGatewayWiringTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private FakeAgentOrchestrator orchestrator;

    @Test
    void fakeGatewayIsDefaultWhenPropertyMissing() {
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(FakeModelAdapter.class);
    }

    @Test
    void openCodeGatewayIsNotRegisteredByDefault() {
        assertThat(context.getBeansOfType(OpenCodeZenModelGateway.class)).isEmpty();
    }

    @Test
    void orchestratorIsWiredThroughTheGatewayAbstraction() {
        assertThat(orchestrator).isNotNull();
    }

    @Test
    void onlyOneGatewayCandidateIsActive() {
        assertThat(context.getBeansOfType(ModelGateway.class)).hasSize(1);
    }
}

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.model.gateway=fake")
class ExplicitFakeModelGatewayWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void explicitFakeGatewaySelectsFake() {
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(FakeModelAdapter.class);
    }
}

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.model.gateway=opencode")
class ExplicitOpenCodeModelGatewayWiringTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @SpyBean
    private OpenCodeZenModelGateway openCodeGateway;

    /**
     * The no-credential tests must own their credential state: if a live smoke
     * or manual seed left an OpenCode credential in the local dev/test
     * database, the real gateway would resolve it and hit the public API
     * instead of failing fast with NOT_CONFIGURED.
     */
    @BeforeEach
    void clearOpenCodeCredential() {
        OpenCodeSettingsCleanup.clear(jdbcTemplate);
    }

    @Test
    void explicitOpenCodeGatewaySelectsOpenCode() {
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OpenCodeZenModelGateway.class);
        assertThat(context.getBeansOfType(FakeModelAdapter.class)).isEmpty();
    }

    @Test
    void orchestratorUsesOpenCodeGatewayWhenConfigured() {
        Project project = projectService.createProject("opencode wiring");

        // No credential is stored, so the real gateway fails fast with
        // NOT_CONFIGURED; the point is that the default runtime path actually
        // calls the OpenCode gateway, not the fake.
        assertThatThrownBy(() -> orchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(OpenCodeModelException.class)
                .hasMessageContaining("settings");

        verify(openCodeGateway).run(any(ModelRequest.class));

        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void runtimeRejectsUnexpectedRealGatewayAction() {
        // The real gateway path: the model proposes an action the runtime did
        // not expect for DRAFT_NODE. The runtime must reject it, fail the run
        // and persist nothing derived from the proposal. doAnswer is used so
        // the spy's real method (which fails on the missing credential) is
        // never invoked while stubbing.
        doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(request.agentRunId(), request.contextSnapshotId(),
                    request.taskType(), AgentAction.STOP, "{\"question\":\"what?\"}",
                    Map.of("adapter", "opencode"));
        }).when(openCodeGateway).run(any(ModelRequest.class));
        Project project = projectService.createProject("unexpected action");

        assertThatThrownBy(() -> orchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Expected ASK_NEXT_QUESTION");

        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.producedNodeId()).isNull();

        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isNull();
    }
}
