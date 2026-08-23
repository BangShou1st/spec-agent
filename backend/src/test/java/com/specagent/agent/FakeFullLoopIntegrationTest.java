package com.specagent.agent;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Success-path integration tests for the fake full loop through the async
 * ANSWER_CYCLE: question draft, answer, answer patch, next node, and spec
 * snapshot generation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeFullLoopIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;

    @Test
    void fullFakeLoopFromQuestionToAnswerPatchToNextNode() {
        Project project = projectService.createProject("Full loop project");

        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        UUID answeredNodeId = first.producedNode().id();

        var result = answerDriver.submitFreeText(
                project.id(), "I need to clarify the main outcome");

        // Run completed and recorded all produced ids.
        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.run().producedAnswerId()).isNotNull();
        assertThat(result.run().producedPatchId()).isNotNull();
        assertThat(result.run().producedNodeId()).isNotNull();

        // Answer persisted.
        Answer answer = answerService.getAnswer(result.answerId()).orElseThrow();
        assertThat(answer.nodeId()).isEqualTo(answeredNodeId);
        assertThat(answer.freeText()).isEqualTo("I need to clarify the main outcome");

        // Patch persisted with real provenance.
        AnswerPatch patch = answerPatchService.getPatch(result.patchId()).orElseThrow();
        assertThat(patch.sourceNodeId()).isEqualTo(answeredNodeId);
        assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
        assertThat(patch.claims()).isNotEmpty();

        AnswerPatch persistedPatch = answerPatchService.findByRoute(project.activeRouteId()).stream()
                .filter(p -> p.id().equals(patch.id()))
                .findFirst().orElseThrow();
        Claim confirmed = persistedPatch.claims().stream()
                .filter(Claim::isConfirmed)
                .findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(answeredNodeId);
        assertThat(confirmed.sourceAnswerId()).isEqualTo(answer.id());

        // Next node exists and extends the answered node.
        Node nextNode = nodeService.getNode(result.producedNodeId()).orElseThrow();
        assertThat(nextNode.parentNodeId()).isEqualTo(answeredNodeId);

        // Route tip advanced to the next node.
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(nextNode.id());

        // A fresh context built from the active route includes everything.
        ContextSnapshot context = contextBuilder.buildFromActiveRoute(
                project.id(), result.run().id(), ContextOperationType.NORMAL);
        assertThat(context.includedNodeIds()).contains(answeredNodeId, nextNode.id());
        assertThat(context.includedAnswerIds()).contains(answer.id());
        assertThat(context.includedPatchIds()).contains(patch.id());
    }

    @Test
    void fakeAnswerRunPersistsAnswerAndPatch() {
        Project project = projectService.createProject("Answer run project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());

        var result = answerDriver.submitFreeText(
                project.id(), "The primary outcome must be measurable");

        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(answerService.getAnswer(result.answerId())).isPresent();
        assertThat(answerPatchService.getPatch(result.patchId())).isPresent();
        assertThat(result.run().producedAnswerId()).isEqualTo(result.answerId());
        assertThat(result.run().producedPatchId()).isEqualTo(result.patchId());
        assertThat(nodeService.getNode(result.run().producedNodeId())).isPresent();
    }

    @Test
    void fakeSpecRunCreatesGroundedSpecSnapshot() {
        Project project = projectService.createProject("Spec run project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "I need to clarify the main outcome");

        FakeSpecRunResult result = fakeAgentOrchestrator.generateSpec(project.id());

        assertThat(result.specSnapshot()).isNotNull();
        assertThat(result.specSnapshot().routeId()).isEqualTo(project.activeRouteId());
        assertThat(result.specSnapshot().contextSnapshotId()).isEqualTo(result.contextSnapshot().id());
        assertThat(result.specSnapshot().sections()).isNotEmpty();
        assertThat(result.specSnapshot().sourceRefs()).isNotEmpty();
        assertThat(result.specSnapshot().sourceRefs())
                .anyMatch(ref -> ref.kind() == SourceKind.CONTEXT
                        && ref.refId().equals(result.contextSnapshot().id()));
        assertThat(result.specSnapshot().createdByRunId()).isEqualTo(result.run().id());

        assertThat(specSnapshotService.getSnapshot(result.specSnapshot().id())).isPresent();
        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.run().producedSpecSnapshotId()).isEqualTo(result.specSnapshot().id());
    }
}
