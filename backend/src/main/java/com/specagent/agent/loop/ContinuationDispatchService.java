package com.specagent.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>Transaction shape (minimal, no framework): each evaluation runs child
 * creation plus the exact-generation mark inside one explicit
 * {@link TransactionTemplate} transaction — never a self-invoked
 * {@code @Transactional} proxy method, so the atomicity holds no matter how
 * this bean is called. A mark-phase failure rolls the whole evaluation back:
 * the pending check stays pending and recovery safely replays it. A crash
 * between child creation and the mark leaves a pending row with a child
 * already present; recovery then observes {@code ALREADY_CONTINUED} and marks
 * processed without creating a second child (V23 single-child index plus the
 * deterministic {@code continue:<parentRunId>} key arbitrate).
 *
 * <p>Generation gate: completion marks exactly the generation it evaluated.
 * A concurrent re-request (approval accept reopening a parked check)
 * increments the generation first, so the stale completion marks 0 rows and
 * the new generation stays pending until recovery converges it.
 */
@Service
public class ContinuationDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationDispatchService.class);

    private final ContinuationCheckRepository checkRepository;
    private final ContinuationCoordinator coordinator;
    private final TransactionTemplate transactionTemplate;

    public ContinuationDispatchService(ContinuationCheckRepository checkRepository,
                                       ContinuationCoordinator coordinator,
                                       TransactionTemplate transactionTemplate) {
        this.checkRepository = checkRepository;
        this.coordinator = coordinator;
        this.transactionTemplate = transactionTemplate;
    }

    /** Requests evaluation for a terminal run (joins the terminal txn). */
    public void request(UUID runId) {
        checkRepository.request(runId);
    }

    /**
     * Evaluates the pending generation for one run: creates the child when
     * eligible, then marks exactly that generation processed — both in one
     * explicit transaction. Transient failures propagate without marking, so
     * the recovery scanner retries; an already-created child converges via
     * {@code ALREADY_CONTINUED} to marking without a second row. A duplicate
     * delivery of an already-processed check is a no-op (the terminal run
     * itself is never re-executed — see {@code RunWorker} fail-closed).
     */
    public void process(UUID runId) {
        ContinuationCheck check = checkRepository.findPendingByRunId(runId)
                .orElse(null);
        if (check == null) {
            return;
        }
        process(check);
    }

    /**
     * Evaluates one pending check generation. Package-visible for the
     * generation-race test: it pins the exact ABA interleaving (generation 1
     * in flight while generation 2 is requested) that the public path can
     * only reach through timing.
     */
    void process(ContinuationCheck check) {
        transactionTemplate.executeWithoutResult(status -> {
            coordinator.continueIfEligible(check.runId());
            checkRepository.markProcessed(check.runId(), check.generation());
        });
    }

    /** Replays pending checks oldest-first; one bad row never blocks others. */
    public void recoverPending() {
        recoverPending(100);
    }

    public void recoverPending(int limit) {
        List<ContinuationCheck> pending = checkRepository.findPending(limit);
        for (ContinuationCheck check : pending) {
            try {
                process(check);
            } catch (RuntimeException ex) {
                LOG.warn("Continuation recovery deferred for run {}: {}",
                        check.runId(), ex.getMessage());
            }
        }
    }
}
