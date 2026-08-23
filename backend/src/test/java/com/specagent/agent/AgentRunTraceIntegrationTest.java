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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a run's final trace carries the major lifecycle steps instead
 * of being overwritten by the last step. The answer scenario drives the async
 * ANSWER_CYCLE; only the spec failure test stubs the fake model.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunTraceIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private Json json;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void answerRunTraceContainsMajorSteps() {
        Project project = projectService.createProject("Trace answer project");
        draftDriver.draftQuestion(project.id());

        var result = answerDriver.submitFreeText(project.id(), "trace the answer loop");

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("persisted_answer")
                .contains("persisted_patch")
                .contains("completed");
        // Run events carry the phase progression of the 2-call cycle.
    }

    @Test
    void specRunTraceContainsMajorSteps() {
        Project project = projectService.createProject("Trace spec project");
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "trace the spec loop");

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
        draftDriver.draftQuestion(project.id());

        // A spec draft without any source reference is rejected by the
        // grounding gate before anything may persist.
        doAnswerInvalidSpec();

        assertThatThrownBy(() -> fakeAgentOrchestrator.generateSpec(project.id()))
                .isInstanceOf(ModelContractException.class);

        AgentRun run = agentRunService.listByProject(project.id()).get(1);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("model_called")
                .contains("failed");
    }

    private void doAnswerInvalidSpec() {
        org.mockito.Mockito.doAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(
                    request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                    AgentAction.GENERATE_SPEC,
                    json.write(new com.specagent.agent.contracts.SpecDraft(
                            java.util.Map.of("Overview", "ungrounded"),
                            java.util.List.of(),
                            java.util.Map.of())),
                    java.util.Map.of("adapter", "spy"));
        }).when(fakeModelAdapter).run(org.mockito.ArgumentMatchers.any(ModelRequest.class));
    }
}
