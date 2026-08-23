package com.specagent.agent.policy;

/**
 * A terminal lifecycle transition lost the single-winner race: the proposal
 * already left PROPOSED through another decision (accept, reject, or expire).
 *
 * <p>This is an expected concurrency outcome, not an internal failure — the
 * loser must surface as a deterministic business error (mapped to
 * {@code 409 PROPOSAL_ALREADY_DECIDED}), never as a 500, SQL constraint
 * violation, or silent fake success.
 */
public class ProposalAlreadyDecidedException extends RuntimeException {

    private final String currentStatus;

    public ProposalAlreadyDecidedException(String currentStatus) {
        super("Proposal has already been decided: " + currentStatus);
        this.currentStatus = currentStatus;
    }

    /** Committed status the winner left behind, e.g. ACCEPTED / REJECTED / EXPIRED. */
    public String currentStatus() {
        return currentStatus;
    }
}
