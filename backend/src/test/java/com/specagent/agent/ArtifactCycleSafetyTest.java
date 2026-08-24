package com.specagent.agent;

import com.specagent.agent.contract.AgentArtifactResponse;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
 * Fail-closed safety of the artifact cycle: a response whose sections are not
 * grounded, or whose source references leave the frozen snapshot's allowed
 * set, fails the run before any snapshot persists. The spy keeps production
 * behavior for everything it does not stub.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactCycleSafetyTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private com.specagent.agent.runtime.RunService runService;
    @Autowired
    private com.specagent.agent.runtime.RunWorker worker;
    @Autowired
    private com.specagent.spec.SpecSnapshotRepository specSnapshotRepository;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private UUID enqueueArtifact(Project project) {
        return runService.createQueuedArtifactGeneration(project.id()).id();
    }

    private void execute(UUID runId) {
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(runId))
                .orElseThrow();
        worker.executeRun(claimed);
    }

    @Test
    void ungroundedSectionFailsTheRunWithoutPersistingASnapshot() {
        Project project = projectService.createProject("Artifact grounding project");
        draftDriver.draftQuestion(project.id());
        UUID runId = enqueueArtifact(project);

        var section = new AgentArtifactResponse.ArtifactSection(
                "Overview", "ungrounded content", List.of());
        stubArtifact(section);
        assertThatThrownBy(() -> execute(runId))
                .isInstanceOf(com.specagent.agent.ModelContractException.class);

        assertFailedAndNothingPersisted(runId, project);
    }

    @Test
    void sourceRefOutsideTheSnapshotFailsTheRun() {
        Project project = projectService.createProject("Artifact source ref project");
        draftDriver.draftQuestion(project.id());
        UUID runId = enqueueArtifact(project);

        var section = new AgentArtifactResponse.ArtifactSection(
                "Overview", "content citing a foreign ref",
                List.of("context:" + UUID.randomUUID()));
        stubArtifact(section);
        // A stubbed engine bypasses the engine-side validator, so this proves
        // the runtime's own source-reference guard fails closed too.
        assertThatThrownBy(() -> execute(runId))
                .isInstanceOf(com.specagent.agent.ModelContractException.class)
                .hasMessageContaining("source reference guard rejected");

        assertFailedAndNothingPersisted(runId, project);
    }

    private void stubArtifact(AgentArtifactResponse.ArtifactSection section) {
        org.mockito.Mockito.doAnswer(invocation -> new AgentArtifactResponse(
                com.specagent.agent.contract.AgentProtocol.ARTIFACT_PROTOCOL_VERSION,
                ((AgentRequestEnvelope) invocation.getArgument(0)).runId(),
                new AgentArtifactResponse.ArtifactGenerationResult(
                        "spec_snapshot", List.of(section), List.of()),
                new com.specagent.agent.contract.UsageView(1, List.of())))
                .when(decisionEngine).runArtifactGeneration(
                        any(AgentRequestEnvelope.class));
    }

    private void assertFailedAndNothingPersisted(UUID runId, Project project) {
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(com.specagent.agent.AgentRunStatus.FAILED);
        assertThat(agentRunService.getRun(runId).orElseThrow().producedSpecSnapshotId())
                .isNull();
        assertThat(specSnapshotRepository.findByRoute(project.activeRouteId())).isEmpty();
    }
}
