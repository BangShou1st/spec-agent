package com.specagent.agent.runtime;

import com.specagent.common.PreciseConflictException;

/** Acceptance cannot mutate the execution currently producing its proposal. */
public class ProposalExecutionConflictException extends PreciseConflictException {
    public ProposalExecutionConflictException() {
        super("The proposal origin is still executing or no longer matches the project");
    }
}
