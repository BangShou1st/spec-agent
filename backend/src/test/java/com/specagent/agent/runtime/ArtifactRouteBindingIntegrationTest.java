package com.specagent.agent.runtime;

import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.contract.AgentArtifactResponse;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.context.ContextSnapshotRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * P1 route-binding regression coverage: an artifact run is bound to the
 * route/tip it was enqueued against from snapshot build to persistence.
 * Switching the active route while the run is queued fails the run closed
 * (no snapshot), and a successful run's context + spec snapshot always carry
 * exactly the run's own route identity and tip.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactRouteBindingIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private ContextSnapshotRepository contextSnapshotRepository;
    @Autowired
    private com.specagent.spec.SpecSnapshotRepository specSnapshotRepository;
    @Autowired
    private com.specagent.answer.AnswerService answerService;
    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private Project seededProject() {
        Project project = projectService.createProject("Route binding " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        // Fork requires a finalized answer at the branch point.
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(),
                null, "seed answer", "user");
        return project;
    }

    @Test
    void artifactRunFailsClosedWhenActiveRouteChangesBeforeExecution() {
        Project project = seededProject();
        UUID routeA = project.activeRouteId();
        UUID tipA = routeService.getRoute(routeA).orElseThrow().tipNodeId();

        UUID runId = runService.createQueuedArtifactGeneration(project.id()).id();

        // User switches the active route while the artifact run waits.
        var routeB = routeService.forkFromNode(project.id(), routeA, tipA, "switched");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(routeB.id());

        stubSuccessfulArtifact();
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(runId))
                .orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(StaleRunTargetException.class)
                .hasMessageContaining("Active route changed");

        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(specSnapshotRepository.findByRoute(routeA)).isEmpty();
        assertThat(specSnapshotRepository.findByRoute(routeB.id())).isEmpty();
    }

    @Test
    void artifactSnapshotIsBoundToRunRoute() {
        Project project = seededProject();
        UUID routeId = project.activeRouteId();
        UUID inputNodeId = routeService.getRoute(routeId).orElseThrow().tipNodeId();

        UUID runId = runService.createQueuedArtifactGeneration(project.id()).id();
        stubSuccessfulArtifact();

        // Even if another route became active between enqueue and claim, the
        // persisted context/snapshot must still bind to the RUN's route.
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(runId))
                .orElseThrow();
        worker.executeRun(claimed);

        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var run = agentRunService.getRun(runId).orElseThrow();
        var contextSnapshot = contextSnapshotRepository.findById(run.contextSnapshotId()).orElseThrow();
        var specSnapshot = specSnapshotRepository.findById(run.producedSpecSnapshotId()).orElseThrow();

        assertThat(run.routeId()).isEqualTo(routeId);
        assertThat(contextSnapshot.routeId()).isEqualTo(run.routeId());
        assertThat(contextSnapshot.tipNodeId()).isEqualTo(inputNodeId);
        assertThat(specSnapshot.routeId()).isEqualTo(run.routeId());
        assertThat(specSnapshot.tipNodeId()).isEqualTo(inputNodeId);
        assertThat(specSnapshot.contextSnapshotId()).isEqualTo(contextSnapshot.id());
    }

    @Test
    void specSourceGuardRejectsRouteSnapshotMismatch() {
        // Direct guard-level proof: route A + snapshot of B must be rejected
        // before per-ref validation, even when every ref would resolve.
        var guard = new com.specagent.agent.gates.SpecSourceReferenceGuard(
                org.mockito.Mockito.mock(com.specagent.route.RouteRepository.class),
                org.mockito.Mockito.mock(com.specagent.node.NodeRepository.class),
                org.mockito.Mockito.mock(com.specagent.answer.AnswerRepository.class),
                org.mockito.Mockito.mock(com.specagent.patch.AnswerPatchRepository.class));

        UUID projectId = UUID.randomUUID();
        UUID routeA = UUID.randomUUID();
        UUID routeB = UUID.randomUUID();
        var snapshotOfB = new com.specagent.context.ContextSnapshot(
                UUID.randomUUID(), projectId, routeB, UUID.randomUUID(),
                com.specagent.context.ContextOperationType.NORMAL,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "hash",
                java.time.Instant.now());

        // The ref itself is even a real NODE ref that would pass per-ref
        // checks against snapshotOfB — only the route pairing is wrong.
        var result = guard.validate(projectId, routeA, snapshotOfB,
                List.of(com.specagent.spec.SourceReference.of(
                        com.specagent.spec.SourceKind.CONTEXT, snapshotOfB.id())));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(error -> error.contains("does not belong to route " + routeA));
        assertThat(result.errors()).anyMatch(error -> error.contains("does not belong to route"));
    }

    private void stubSuccessfulArtifact() {
        org.mockito.Mockito.doAnswer(invocation -> {
            var envelope = (AgentRequestEnvelope) invocation.getArgument(0);
            return new AgentArtifactResponse(
                com.specagent.agent.contract.AgentProtocol.ARTIFACT_PROTOCOL_VERSION,
                envelope.runId(),
                new AgentArtifactResponse.ArtifactGenerationResult(
                        "spec_snapshot",
                        List.of(new AgentArtifactResponse.ArtifactSection(
                                "Overview", "grounded content",
                                List.of("route:" + envelope.snapshot().routeId()))),
                        List.of()),
                new com.specagent.agent.contract.UsageView(1, List.of()));
        })
                .when(decisionEngine).runArtifactGeneration(any(AgentRequestEnvelope.class));
    }
}
