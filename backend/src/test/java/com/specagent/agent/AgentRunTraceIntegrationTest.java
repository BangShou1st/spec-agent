package com.specagent.agent;

import com.specagent.common.Json;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.testing.FakeModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Verifies that a run's final trace carries the major lifecycle steps instead
 * of being overwritten by the last step. Only the failure test stubs the fake
 * model; the other two run against the real deterministic adapter.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunTraceIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private Json json;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void answerRunTraceContainsMajorSteps() {
        Project project = projectService.createProject("Trace answer project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());

        FakeAnswerRunResult result = fakeAgentOrchestrator.answerActiveNodeAndDraftNext(
                project.id(), "trace the answer loop");

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("model_called")
                .contains("reflected")
                .contains("persisted_answer")
                .contains("persisted_patch")
                .contains("persisted_node")
                .contains("completed");
    }

    @Test
    void specRunTraceContainsMajorSteps() {
        Project project = projectService.createProject("Trace spec project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        fakeAgentOrchestrator.answerActiveNodeAndDraftNext(project.id(), "trace the spec loop");

        FakeSpecRunResult result = fakeAgentOrchestrator.generateSpec(project.id());

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("model_called:DRAFT_SPEC")
                .contains("reflected:SPEC_GROUNDING")
                .contains("reflected:SOURCE_REFERENCES")
                .contains("persisted_spec_snapshot")
                .contains("completed");
    }

    @Test
    void failedRunTraceContainsFailureStep() {
        Project project = projectService.createProject("Trace failure project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());

        // Only DRAFT_ANSWER_PATCH is made invalid; everything else runs the
        // real deterministic fake adapter. The invalid patch carries a blank
        // claim text in the model-facing shape (no runtime-owned ids), which the
        // strict output parser rejects before the patch can be reflected.
        doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            if (request.taskType() != AgentTaskType.DRAFT_ANSWER_PATCH) {
                return invocation.callRealMethod();
            }
            return new ModelResponse(
                    request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                    AgentAction.INTERPRET_ANSWER,
                    json.write(Map.of("claims", List.of(
                            Map.of("kind", "goal", "text", " ",
                                    "status", "confirmed", "confidence", 0.9)))),
                    Map.of("adapter", "spy"));
        }).when(fakeModelAdapter).run(any(ModelRequest.class));

        assertThatThrownBy(() -> fakeAgentOrchestrator.answerActiveNodeAndDraftNext(
                project.id(), "trace the failure"))
                .isInstanceOf(ModelContractException.class);

        AgentRun run = agentRunService.listByProject(project.id()).get(1);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("model_called")
                .contains("failed");
    }
}
