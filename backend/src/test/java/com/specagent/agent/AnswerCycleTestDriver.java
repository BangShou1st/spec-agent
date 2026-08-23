package com.specagent.agent;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test-only driver for the async ANSWER_CYCLE: enqueues a run and executes it
 * synchronously through the worker. Replaces the retired synchronous
 * {@code AgentOrchestrator.answerActiveNodeAndDraftNext} in test fixtures so
 * recovery/isolation suites exercise exactly the production answer path.
 */
@Component
public class AnswerCycleTestDriver {

    private final RunService runService;
    private final RunWorker worker;

    public AnswerCycleTestDriver(RunService runService, RunWorker worker) {
        this.runService = runService;
        this.worker = worker;
    }

    /**
     * Submits a free-text answer to the active route tip and drives the run to
     * its terminal state. Returns the run plus the produced records.
     */
    public SubmittedAnswer submitFreeText(UUID projectId, String freeText) {
        return submit(projectId, "ANSWER_TIP", null, freeText, null);
    }

    /** Resumes the given persisted answer (repair path). */
    public SubmittedAnswer resumeAnswer(UUID projectId, UUID answerId) {
        return submit(projectId, "RESUME_ANSWER", null, null, answerId);
    }

    /**
     * Enqueues an answer run against a specific node without executing it.
     * Used to stage runs whose target may go stale before the worker claims
     * them (failure-path fixtures).
     */
    public UUID enqueueOnly(UUID projectId, String operation, UUID nodeId,
                            UUID selectedOptionId, String freeText, UUID answerId) {
        return runService.createQueuedRunWithInput(
                projectId, operation, nodeId, selectedOptionId, freeText, answerId);
    }

    private SubmittedAnswer submit(UUID projectId, String operation,
                                   UUID nodeId, String freeText, UUID answerId) {
        UUID runId = runService.createQueuedRunWithInput(
                projectId, operation, nodeId, null, freeText, answerId);
        var claimed = runService.claimNextAnswerCycle()
                .orElseThrow(() -> new IllegalStateException("No queued answer-cycle run"));
        if (!claimed.id().equals(runId)) {
            throw new IllegalStateException("Claimed a different run than was enqueued");
        }
        worker.executeRun(claimed);
        AgentRun run = runService.getRun(runId)
                .orElseThrow(() -> new IllegalStateException("Run disappeared: " + runId));
        return new SubmittedAnswer(run);
    }

    /** Post-run view over one submitted answer cycle. */
    public record SubmittedAnswer(AgentRun run) {

        public UUID answerId() {
            return run.producedAnswerId();
        }

        public UUID patchId() {
            return run.producedPatchId();
        }

        public UUID producedNodeId() {
            return run.producedNodeId();
        }
    }
}
