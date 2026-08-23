package com.specagent.model.gateway;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.ModelRequest;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Runtime-level provider failure diagnostics, zero public network.
 *
 * <p>The question draft runs on the async decision runtime and never crosses
 * the legacy {@code ModelGateway}, so the provider failure diagnostics are
 * exercised through spec generation — the remaining production ModelGateway
 * consumer — after the route tip is seeded with {@link DecisionCycleTestDriver}.
 * The OpenCode gateway spy fails with a real {@link OpenCodeModelException}
 * per category; the orchestrator must fail the run, keep the failure
 * attributable (task + provider category in the trace), persist no derived
 * artifact, and never let the provider message (or any secret that might be
 * inside it) reach the trace.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.model.gateway=opencode")
class OpenCodeProviderFailureDiagnosticsTest {

    /** Deliberately placed inside the exception message to prove it is never persisted. */
    private static final String SENTINEL_KEY = "sk-provider-diag-secret-77aa";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @SpyBean
    private OpenCodeZenModelGateway openCodeGateway;

    @BeforeEach
    void clearOpenCodeCredential() {
        OpenCodeSettingsCleanup.clear(jdbcTemplate);
    }

    @Test
    void providerFailuresFailTheRunWithTaskAndCategoryInTrace() {
        for (OpenCodeModelErrorCategory category : List.of(
                OpenCodeModelErrorCategory.RATE_LIMITED,
                OpenCodeModelErrorCategory.SERVER_ERROR,
                OpenCodeModelErrorCategory.TIMEOUT,
                OpenCodeModelErrorCategory.INVALID_RESPONSE)) {
            stubProviderFailure(category);
            Project project = projectService.createProject("provider failure " + category);
            // Seed the route tip the spec path needs; the draft runs on the
            // async decision runtime and never reaches the gateway.
            draftDriver.draftQuestion(project.id());

            assertThatThrownBy(() -> orchestrator.generateSpec(project.id()))
                    .as("DRAFT_SPEC with provider category %s must throw", category)
                    .isInstanceOf(OpenCodeModelException.class)
                    .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                            .isEqualTo(category));

            AgentRun run = agentRunService.listByProject(project.id()).stream()
                    .filter(r -> r.status() == AgentRunStatus.FAILED)
                    .findFirst()
                    .orElseThrow();
            assertThat(run.status()).as("run for %s", category).isEqualTo(AgentRunStatus.FAILED);
            assertThat(run.trace()).as("trace for %s", category)
                    .contains("context_built")
                    .contains("model_called:DRAFT_SPEC")
                    .contains("failed:provider:" + category);

            // The trace records the category only; the provider message (which
            // could echo payloads) and the key-like sentinel never reach it.
            assertThat(run.trace()).as("no provider message in trace for %s", category)
                    .doesNotContain(SENTINEL_KEY)
                    .doesNotContain("Bearer");

            // The rejected proposal is never persisted: no spec snapshot, and
            // the drafted root stays the route tip.
            assertThat(run.producedSpecSnapshotId()).as("no snapshot persisted for %s", category).isNull();
            Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
            assertThat(route.tipNodeId()).as("route tip for %s", category).isNotNull();
        }
    }

    @Test
    void missingCredentialFailsAsNotConfiguredBeforeAnyProviderCall() {
        // No credential and no stub: the real gateway must fail fast with
        // NOT_CONFIGURED and the trace must say so.
        Project project = projectService.createProject("not configured");
        draftDriver.draftQuestion(project.id());

        assertThatThrownBy(() -> orchestrator.generateSpec(project.id()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.NOT_CONFIGURED));

        AgentRun run = agentRunService.listByProject(project.id()).stream()
                .filter(r -> r.status() == AgentRunStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace())
                .contains("model_called:DRAFT_SPEC")
                .contains("failed:provider:NOT_CONFIGURED");
    }

    private void stubProviderFailure(OpenCodeModelErrorCategory category) {
        doAnswer(invocation -> {
            throw new OpenCodeModelException(category,
                    "OpenCode request failed (" + category + ") " + SENTINEL_KEY);
        }).when(openCodeGateway).run(any(ModelRequest.class));
    }
}
