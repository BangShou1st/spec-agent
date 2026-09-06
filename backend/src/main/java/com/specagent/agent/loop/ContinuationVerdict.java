package com.specagent.agent.loop;

/**
 * Neutral Runtime outcome of one finished AgentRun, as seen by the
 * continuation coordinator.
 *
 * <p>Every value describes what the Runtime durably did — never what the
 * agent should do next. Each value's proof is the durable row or event
 * listed below; a verdict that cannot name its proof must not be added.
 * No verdict reads model-generated text (observations, claims, conflicts,
 * goals, planning flags).
 */
public enum ContinuationVerdict {

    /**
     * Proof: {@code agent_runs.produced_node_id / produced_answer_id /
     * produced_patch_id / produced_spec_snapshot_id} non-null, or a
     * {@code capability_invocations} row for this run with completed status
     * ({@code SUCCEEDED}, or {@code FAILED} — failures persist as evidence
     * and enter future snapshots). A next snapshot can consume new facts.
     */
    EXECUTED_NEW_OBSERVATION,

    /**
     * Proof: this run's {@code produced_node_id} names an existing
     * {@code INTERACTION} node with no finalized answer row for the run's
     * route. The question waits on an external observation (user answer
     * via {@code ANSWER_CYCLE}), not on Runtime work.
     */
    PARKED_USER_INPUT,

    /**
     * Proof: an {@code agent_proposals} row for this run still in
     * {@code PROPOSED} status. Decided rows ({@code ACCEPTED},
     * {@code REJECTED}, {@code EXPIRED}, {@code MODIFIED}) do not park:
     * the run is re-judged from current durable state instead.
     */
    PARKED_APPROVAL,

    /**
     * Proof: a {@code RESPOND_MESSAGE} event row for this run. The cycle
     * answered and emitted nothing else durable; v1 ends the chain here.
     */
    TERMINAL_RESPONSE,

    /**
     * Proof: an {@code EXPIRED} proposal row for this run with no other
     * durable effect (policy-deny branches create-then-expire), or a
     * {@code POLICY_DENIED} / {@code MUTATION_NOT_CONFIRMABLE} event row.
     * Nothing changed, so a next cycle would decide on identical facts.
     */
    DENIED,

    /**
     * Proof: the {@code agent_runs} row status is {@code FAILED}.
     */
    FAILED,

    /**
     * Proof: none of the above rows or events exists for this run. The
     * cycle terminalized without a durable effect the Runtime can name.
     */
    NO_EFFECT,

    /**
     * Proof: persisted {@code cycle_index} (null reads as 0) plus the
     * configured {@code spec.agent.loop.max-cycles} satisfy
     * {@code cycleIndex + 1 >= maxCycles}. The chain ends normally —
     * budget exhaustion is a volume bound, never a failure.
     */
    BUDGET_EXHAUSTED,

    /**
     * Proof: an {@code agent_runs} row with {@code parent_run_id} equal to
     * this run already exists. The terminal boundary was already continued
     * once; a second child would fork the chain.
     */
    ALREADY_CONTINUED;

    public String code() {
        return name();
    }
}
