package com.specagent.agent;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test-only driver for the async question draft: enqueues a DRAFT_QUESTION
 * run and executes it synchronously through the worker. Replaces the retired
 * synchronous {@code AgentOrchestrator.draftNextQuestion} in test fixtures so
 * isolation/recovery suites exercise exactly the production draft path.
 */
@Component
public class DecisionCycleTestDriver {

    private final RunService runService;
    private final RunWorker worker;

    public DecisionCycleTestDriver(RunService runService, RunWorker worker) {
        this.runService = runService;
        this.worker = worker;
    }

    /**
     * Drafts the next question on the project's active route and drives the
     * run to its terminal state. The claim is targeted at the enqueued run id
     * so an unrelated queued row can never be picked up instead.
     */
    public AgentRun draftQuestion(UUID projectId) {
        AgentRun enqueued = runService.createQueuedDraftQuestion(projectId);
        var claimed = runService.claimDecisionCycleRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + enqueued.id()));
        worker.executeRun(claimed);
        return runService.getRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Run disappeared: " + enqueued.id()));
    }
}
