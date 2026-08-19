package com.specagent.agent;

import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.common.Json;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the answer repair path: a failed answer-processing run
 * leaves the immutable answer persisted, and the repair run resumes patch and
 * next-node processing from that existing answer without finalizing a second
 * one.
 *
 * <p>Deliberately not {@code @Transactional}: the failure phase throws inside
 * the test, and the whole point is that the FAILED run and the persisted answer
 * stay queryable afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class FakeAnswerRepairIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private Json json;

    @MockBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void repairAnswerProcessingAfterPatchRejectionCompletesLoop() {
        Project project = projectService.createProject("Repair project");
        AtomicBoolean invalidPatch = new AtomicBoolean(true);

        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            Map<String, String> trace = Map.of("adapter", "mock");
            return switch (request.taskType()) {
                case DRAFT_NODE -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.ASK_NEXT_QUESTION,
                        json.write(new NodeDraft("What is the most important outcome?",
                                "Clarifies the primary requirement goal.", List.of(), true)),
                        trace);
                case INTERPRET_ANSWER -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        json.write(new AnswerInterpretationResult(
                                List.of("The user clarified the main outcome."),
                                List.of(), List.of(), List.of())),
                        trace);
                case DRAFT_ANSWER_PATCH -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        json.write(invalidPatch.get()
                                // Model-facing patch shape: no runtime-owned ids.
                                // A blank claim text violates the strict output
                                // contract, so the parser rejects it before the
                                // patch may be reflected or persisted.
                                ? Map.of("claims", List.of(
                                        Map.of("kind", "goal", "text", " ",
                                                "status", "confirmed", "confidence", 0.9)))
                                : Map.of("claims", List.of(
                                        Map.of("kind", "goal", "text", "The user clarified the main outcome.",
                                                "status", "confirmed", "confidence", 0.9),
                                        Map.of("kind", "open_question", "text", "Scope must be confirmed.",
                                                "status", "unresolved", "confidence", 0.6)))),
                        trace);
                default -> throw new IllegalStateException("Unexpected task " + request.taskType());
            };
        });

        // First node.
        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        UUID originalNodeId = first.producedNode().id();

        // Phase 1: invalid patch draft -> run FAILED, answer persisted, no patch.
        assertThatThrownBy(() -> fakeAgentOrchestrator.answerActiveNodeAndDraftNext(project.id(), "clarified"))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("non-blank");

        List<AgentRun> runsAfterFailure = agentRunService.listByProject(project.id());
        assertThat(runsAfterFailure).hasSize(2);
        AgentRun failedRun = runsAfterFailure.get(1);
        assertThat(failedRun.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failedRun.producedAnswerId()).isNotNull();
        assertThat(failedRun.producedPatchId()).isNull();

        Answer existingAnswer = answerService.getAnswer(failedRun.producedAnswerId()).orElseThrow();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();
        Route routeAfterFailure = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(routeAfterFailure.tipNodeId()).isEqualTo(originalNodeId);

        // Phase 2: repair from the existing answer.
        invalidPatch.set(false);
        FakeAnswerRunResult repaired = fakeAgentOrchestrator.repairAnswerProcessingAndDraftNext(
                project.id(), existingAnswer.id());

        // No second answer was created.
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(originalNodeId)))
                .hasSize(1);

        // Patch created with real provenance on the existing answer.
        AnswerPatch patch = answerPatchService.getPatch(repaired.patch().id()).orElseThrow();
        assertThat(patch.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(patch.sourceAnswerId()).isEqualTo(existingAnswer.id());
        Claim confirmed = patch.claims().stream().filter(Claim::isConfirmed).findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(confirmed.sourceAnswerId()).isEqualTo(existingAnswer.id());

        // Next node created and route tip advanced.
        assertThat(repaired.producedNode()).isNotNull();
        assertThat(repaired.producedNode().parentNodeId()).isEqualTo(originalNodeId);
        Route finalRoute = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(finalRoute.tipNodeId()).isEqualTo(repaired.producedNode().id());

        // Repair run completed and recorded all produced ids.
        assertThat(repaired.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(repaired.run().producedAnswerId()).isEqualTo(existingAnswer.id());
        assertThat(repaired.run().producedPatchId()).isEqualTo(patch.id());
        assertThat(repaired.run().producedNodeId()).isEqualTo(repaired.producedNode().id());
        assertThat(agentRunService.listByProject(project.id())).hasSize(3);
    }

    @Test
    void repairRejectsAnswerFromInactiveRouteOrForeignProject() {
        Project projectA = projectService.createProject("Repair foreign project");
        Node node = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                projectA.id(), projectA.activeRouteId(), node.id(), null, "clarified", "user");

        // Foreign project: answer belongs to project A, not project B.
        Project projectB = projectService.createProject("Repair foreign project B");
        assertThatThrownBy(() -> fakeAgentOrchestrator.repairAnswerProcessingAndDraftNext(projectB.id(), answer.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to project");

        // Inactive route: forking makes a new route active, the answer's route is no longer active.
        Route forkRoute = routeService.forkFromNode(projectA.id(), projectA.activeRouteId(), node.id(), "sibling route");
        assertThat(projectService.getProject(projectA.id()).orElseThrow().activeRouteId())
                .isEqualTo(forkRoute.id());
        assertThatThrownBy(() -> fakeAgentOrchestrator.repairAnswerProcessingAndDraftNext(projectA.id(), answer.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active route");
    }
}
