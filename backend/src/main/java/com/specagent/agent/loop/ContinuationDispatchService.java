package com.specagent.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Durable continuation dispatcher: the low-latency fast path plus the
 * crash-recovery scanner over {@code agent_run_continuation_checks}.
 *
 * <p>Only dependencies are the check outbox and the coordinator — no model,
 * route, context, action, approval, or semantic planning input. Every
 * decision re-reads durable run facts via
 * {@link ContinuationCoordinator#continueIfEligible(UUID)}, so a replay after
 * a crash returns the same answer as the lost afterCommit.
 *
 * <p>Idempotency: the child row is created first, then the check is marked
 * processed. A crash between the two leaves a pending row with a child
 * already present; recovery then observes {@code ALREADY_CONTINUED} and marks
 * processed without creating a second child (V23 single-child index plus the
 * deterministic {@code continue:<parentRunId>} key arbitrate).
 */
@Service
public class ContinuationDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationDispatchService.class);

    private final ContinuationCheckRepository checkRepository;
    private final ContinuationCoordinator coordinator;

    public ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                       ContinuationCoordinator coordinator) {
        this.checkRepository = checkRepository;
        this.coordinator = coordinator;
    }

    /** Requests evaluation for a terminal run (joins the terminal txn). */
    public void request(UUID runId) {
        checkRepository.request(runId);
    }

    /**
     * Evaluates one check: creates the child when eligible, then marks
     * processed. Transient failures propagate without marking, so the
     * recovery scanner retries; an already-created child converges via
     * {@code ALREADY_CONTINUED} to marking without a second row.
     */
    @Transactional
    public void process(UUID runId) {
        coordinator.continueIfEligible(runId);
        checkRepository.markProcessed(runId);
    }

    /** Replays pending checks oldest-first; one bad row never blocks others. */
    public void recoverPending() {
        recoverPending(100);
    }

    public void recoverPending(int limit) {
        List<UUID> pending = checkRepository.findPending(limit);
        for (UUID runId : pending) {
            try {
                process(runId);
            } catch (RuntimeException ex) {
                LOG.warn("Continuation recovery deferred for run {}: {}",
                        runId, ex.getMessage());
            }
        }
    }
}
