package com.specagent.agent;

import com.specagent.common.Json;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private Json json;

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



}
