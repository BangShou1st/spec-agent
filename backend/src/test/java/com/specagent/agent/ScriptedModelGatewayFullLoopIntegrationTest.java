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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Non-live scripted full-loop integration tests: the fake model adapter is
 * scripted with model-facing {@code {action, output}} responses so the full
 * loop exercises the normal parser / mapper / reflection / persistence order
 * without touching the public network.
 *
 * <p>These two tests close the failure-lifecycle gaps between steps: a provider
 * failure after the answer is persisted but before the patch, and a provider
 * failure after the patch is persisted but before the next node. Wrong action,
 * invalid structured output, spec grounding rejection and invalid source
 * references are already covered by {@link FakeAgentOrchestratorFailureIntegrationTest}
 * and {@link FakeFullLoopFailureIntegrationTest}.
 *
 * <p>Deliberately not {@code @Transactional}: a FAILED agent run must stay
 * queryable after the surrounding agent cycle fails, and the immutable answer
 * and already-persisted patch must survive the failed run.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptedModelGatewayFullLoopIntegrationTest {

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
    void providerFailureAfterAnswerPersistedButBeforePatchKeepsAnswerAndNoPatch() {
        Project project = projectService.createProject("Provider failure before patch");
        Node rootNode = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", "Clarify the outcome", List.of(), true);

        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            Map<String, String> trace = Map.of("adapter", "mock");
            if (request.taskType() == AgentTaskType.INTERPRET_ANSWER) {
                throw new RuntimeException("provider exploded during interpretation");
            }
            return new ModelResponse(
                    request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                    AgentAction.INTERPRET_ANSWER,
                    json.write(new AnswerInterpretationResult(
                            List.of("confirmed text"), List.of(), List.of(), List.of())),
                    trace);
        });

        assertThatThrownBy(() -> fakeAgentOrchestrator.answerActiveNodeAndDraftNext(project.id(), "clarified"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("provider exploded");

        // The run is FAILED, the trace keeps the steps that did happen, and
        // only the immutable answer is persisted: no patch, no next node.
        List<AgentRun> runs = agentRunService.listByProject(project.id());
        assertThat(runs).hasSize(1);
        AgentRun run = runs.get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.trace()).contains("persisted_answer", "failed:");
        assertThat(run.producedAnswerId()).isNotNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedNodeId()).isNull();

        // The already-finalized answer stays immutable and queryable.
        Answer answer = answerService.getAnswer(run.producedAnswerId()).orElseThrow();
        assertThat(answer.nodeId()).isEqualTo(rootNode.id());
        assertThat(answer.freeText()).isEqualTo("clarified");
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(rootNode.id())))
                .hasSize(1);

        // No rejected/aborted artifact entered requirement state and the route
        // tip is untouched.
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(rootNode.id());
    }

    @Test
    void providerFailureAfterPatchPersistedButBeforeNextNodeKeepsPatchAndAnswer() {
        Project project = projectService.createProject("Provider failure before next node");

        AtomicInteger nodeCalls = new AtomicInteger();
        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            Map<String, String> trace = Map.of("adapter", "mock");
            return switch (request.taskType()) {
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
                        json.write(Map.of("claims", List.of(
                                Map.of("kind", "goal", "text", "The user clarified the main outcome.",
                                        "status", "confirmed", "confidence", 0.9)))),
                        trace);
                case DRAFT_NODE -> {
                    if (nodeCalls.incrementAndGet() > 1) {
                        throw new RuntimeException("provider exploded while drafting the next node");
                    }
                    yield new ModelResponse(
                            request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                            AgentAction.ASK_NEXT_QUESTION,
                            json.write(new NodeDraft("What is the next question?",
                                    "Continue clarification", List.of(), true)),
                            trace);
                }
                default -> throw new IllegalStateException("Unexpected task " + request.taskType());
            };
        });

        // Draft the root node through the normal path, then fail the answer
        // loop at the post-patch next-node step.
        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        Node answeredNode = first.producedNode();
        assertThatThrownBy(() -> fakeAgentOrchestrator.answerActiveNodeAndDraftNext(project.id(), "clarified"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("provider exploded");

        List<AgentRun> runs = agentRunService.listByProject(project.id());
        assertThat(runs).hasSize(2);
        AgentRun failedRun = runs.get(1);
        assertThat(failedRun.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failedRun.trace()).contains("model_called:DRAFT_ANSWER_PATCH",
                "persisted_patch", "failed:");

        // The accepted patch is not rolled back; it stays persisted with real
        // runtime provenance, and the immutable answer remains.
        List<AnswerPatch> patches = answerPatchService.findByRoute(project.activeRouteId());
        assertThat(patches).hasSize(1);
        AnswerPatch patch = patches.get(0);
        Answer answer = answerService.getAnswer(failedRun.producedAnswerId()).orElseThrow();
        assertThat(answer.nodeId()).isEqualTo(answeredNode.id());
        assertThat(patch.sourceNodeId()).isEqualTo(answeredNode.id());
        assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
        Claim confirmed = patch.claims().stream().filter(Claim::isConfirmed).findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(answeredNode.id());
        assertThat(confirmed.sourceAnswerId()).isEqualTo(answer.id());

        // The rejected next node was not persisted and the route tip is still
        // the answered node.
        assertThat(failedRun.producedNodeId()).isNull();
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(answeredNode.id());
    }
}
