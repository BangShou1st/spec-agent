package com.specagent.eval;

import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.trace.SemanticTraceRecorder;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRunnerProjectCallbackTest {

    @Test
    void projectCallbackRunsBeforeGraphSetupCanThrow() {
        ProjectService projects = mock(ProjectService.class);
        RouteRepository routes = mock(RouteRepository.class);
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        when(projects.createProject(anyString())).thenReturn(new Project(
                projectId, "eval-callback", routeId, UUID.randomUUID(), Instant.now(), Instant.now()));
        // A missing active route makes buildGraph fail immediately after the project is created.
        when(routes.findById(routeId)).thenReturn(java.util.Optional.empty());

        @SuppressWarnings("unchecked")
        ObjectProvider<BrainScriptInstaller> brainScripts = mock(ObjectProvider.class);
        BrainScriptInstaller brain = mock(BrainScriptInstaller.class);
        when(brainScripts.getIfAvailable()).thenReturn(brain);

        ScenarioRunner runner = new ScenarioRunner(
                projects, mock(NodeService.class), mock(RouteService.class), routes,
                mock(GraphCommandService.class), mock(RunService.class), mock(RunWorker.class),
                mock(AgentRunService.class), mock(AgentRunEventService.class), mock(AnswerService.class),
                mock(AnswerRepository.class), mock(AnswerPatchService.class),
                mock(ContextSnapshotRepository.class), mock(AgentProposalService.class), brainScripts,
                mockProvider(), mockProvider(), mock(SemanticTraceRecorder.class));

        AtomicReference<UUID> registeredProject = new AtomicReference<>();
        ScenarioDefinition scenario = scenario();

        assertThatThrownBy(() -> runner.run(scenario, scenario.variants().get(0), registeredProject::set))
                .isInstanceOf(NoSuchElementException.class);

        assertThat(registeredProject.get()).isEqualTo(projectId);
        assertThat(runner.lastProjectId()).isEqualTo(projectId);
        verify(projects).createProject(anyString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
        return mock(ObjectProvider.class);
    }

    private static ScenarioDefinition scenario() {
        return new ScenarioDefinition(
                "P3B-CLEANUP", "1", "project callback exception coverage",
                new GivenSpec(
                        "callback", List.of(new GraphStep.CreateRootQuestion("root", true)),
                        new UserEvent.AnswerTip("answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null), List.of(), List.of(),
                        new BrainScript(List.of(),
                                new BrainDecision("WAIT", Map.of()), List.of(), List.of())),
                new ExpectSpec(Set.of(), List.of(), Set.of("WAIT"), Set.of(),
                        Map.of(), Map.of(), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 1L)));
    }
}
