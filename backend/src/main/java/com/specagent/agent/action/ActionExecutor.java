package com.specagent.agent.action;

import com.specagent.agent.contract.ActionProposal;

/**
 * Executor port for applying validated action proposals to the graph.
 * The executor owns identity assignment and invariant enforcement;
 * proposals carry only content, never runtime-owned IDs.
 *
 * <p>Stage B implements the full nine-family dispatch. Families that
 * cannot execute yet (INVOKE_CAPABILITY, GENERATE_ARTIFACT) return
 * an explicit unsupported result rather than silently succeeding.
 */
public interface ActionExecutor {

    /**
     * Validates and executes the given proposal. Throws
     * {@link StaleProposalException} when the proposal's base context
     * no longer matches the current authoritative graph state.
     */
    ActionResult execute(ActionProposal proposal, ActionExecutionContext context);
}
