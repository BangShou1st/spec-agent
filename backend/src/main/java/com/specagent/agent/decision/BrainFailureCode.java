package com.specagent.agent.decision;

/**
 * Why a brain call did not produce a usable decision.
 *
 * <p>Before this type existed every one of these causes collapsed into a single
 * opaque {@code brain_unavailable} run failure, so a deterministic model-output
 * defect was indistinguishable from the service being down. The code is the
 * stable machine identity carried into the durable run failure; the reason
 * strings are the wire values the eval harness and the UI read.
 *
 * <p>None of these codes authorizes an automatic retry: contract and grounding
 * failures are deterministic for a given output, so they surface as a typed
 * failure plus a manual recovery entry (the run can be resumed from its own
 * checkpoint). Only the brain's single, budget-funded conflict repair retries a
 * model call, and that decision stays inside the brain.
 */
public enum BrainFailureCode {

    /** Unreachable/refused connection, an untyped 5xx, or an empty response. */
    BRAIN_UNAVAILABLE("brain_unavailable"),

    /** The call timed out (connect or read) while the brain was still working. */
    BRAIN_TIMEOUT("brain_timeout"),

    /** The model provider/broker call behind the brain failed. */
    MODEL_PROVIDER_FAILURE("model_provider_failure"),

    /** The model output violated the brain's output contract. */
    MODEL_CONTRACT_VIOLATION("model_contract_violation"),

    /** The model cited a source outside the frozen snapshot's allowed refs. */
    MODEL_UNGROUNDED_REFERENCE("model_ungrounded_reference");

    private final String reasonCode;

    BrainFailureCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    /** Stable code recorded in the run trace and the {@code RUN_FAILED} event. */
    public String reasonCode() {
        return reasonCode;
    }
}
