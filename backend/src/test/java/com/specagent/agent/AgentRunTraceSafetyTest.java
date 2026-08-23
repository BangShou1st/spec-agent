package com.specagent.agent;

import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
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
 * Trace safety on the runtime path, zero public network.
 *
 * <p>The persisted {@link AgentRun} trace must stay diagnosable without ever
 * carrying secrets or raw payloads: no API key, no Authorization header, no
 * user answer text. Provider failures surface as a safe category
 * ({@code failed:provider:<category>}) even when the exception message itself
 * contains secret-like content.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunTraceSafetyTest {

    private static final String SECRET_SENTINEL = "«redacted:sk-…»";
    private static final String ANSWER_SENTINEL = "trace safety answer payload 9k2m";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void successfulRunTraceNeverContainsSecretsOrPayload() {
        Project project = projectService.createProject("trace safety");
        orchestrator.draftNextQuestion(project.id());

        var result = answerDriver.submitFreeText(project.id(), ANSWER_SENTINEL);

        AgentRun run = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.trace())
                .doesNotContain(ANSWER_SENTINEL)
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain("Bearer")
                .doesNotContain("sk-");
    }

    @Test
    void providerFailureCategoryAppearsInTraceWithoutSecretOrMessage() {
        // The spy fails like a provider whose error message unexpectedly echoes
        // a secret: the trace must keep only the safe category.
        org.mockito.Mockito.doAnswer(invocation -> {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                    "OpenCode request failed " + SECRET_SENTINEL);
        }).when(fakeModelAdapter).run(org.mockito.ArgumentMatchers.any(ModelRequest.class));
        Project project = projectService.createProject("trace safety failure");

        assertThatThrownBy(() -> orchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(OpenCodeModelException.class);

        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace())
                .contains("context_built")
                .contains("model_called:DRAFT_NODE")
                .contains("failed:provider:RATE_LIMITED")
                .doesNotContain(SECRET_SENTINEL)
                .doesNotContain("Bearer");
    }
}
