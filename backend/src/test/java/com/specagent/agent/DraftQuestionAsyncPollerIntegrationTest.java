package com.specagent.agent;

import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for BUG-03: the E2E suite exercises the REAL async worker
 * poller ({@code spec.agent.brain.worker.enabled=true}) for DRAFT_QUESTION,
 * whereas every existing backend test drives the worker synchronously through
 * {@link com.specagent.agent.DecisionCycleTestDriver} (targeted claim by id).
 * This test reproduces the E2E scenario: it enqueues a DRAFT_QUESTION run and
 * lets the production {@link RunWorker} poller claim + execute it, then asserts
 * the run reaches COMPLETED with a produced node. No {@code @Transactional} so
 * the run is committed and visible to the background poller thread.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.brain.worker.enabled=true")
class DraftQuestionAsyncPollerIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker runWorker;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private AgentRunEventService eventService;

    @Test
    void draftQuestionRunReachesCompletedViaBackgroundPoller() throws Exception {
        Project project = projectService.createProject("Async draft reproduction");
        AgentRun enqueued = runService.createQueuedDraftQuestion(project.id());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        AgentRun run = null;
        while (Instant.now().isBefore(deadline)) {
            run = agentRunService.getRun(enqueued.id()).orElse(null);
            if (run != null
                    && (run.status() == AgentRunStatus.COMPLETED
                        || run.status() == AgentRunStatus.FAILED)) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(run).isNotNull();
        if (run.status() == AgentRunStatus.FAILED) {
            List<AgentRunEvent> events = eventService.findByRunId(enqueued.id());
            String trace = events.stream()
                    .reduce((first, second) -> second)
                    .map(e -> e.phase().code() + ":" + e.eventType())
                    .orElse("<no-events>");
            assertThat(run.status())
                    .describedAs("DRAFT_QUESTION run must complete; failed at " + trace)
                    .isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.producedNodeId()).isNotNull();
    }
}
