package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.DraftQuestionAsyncPollerIntegrationTest;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
 *
 * <p><b>Isolation.</b> This is the only suite that runs the real
 * {@code RunWorkerPoller} against the shared test database. Spring caches the
 * test ApplicationContext, so without closing it here the {@code @Scheduled}
 * poller would keep running for the rest of the JVM and could claim/execute
 * runs enqueued by unrelated tests (and commit {@code context_snapshots} rows
 * while those tests clean their projects up — observed as a flaky foreign-key
 * violation inside eval cleanup). {@code @DirtiesContext} AFTER_CLASS shuts the
 * context (and its scheduler) down as soon as this class is done.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.brain.worker.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
