package com.specagent.agent.protocol;

import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.protocol.AgentContractException;

/** Fail-closed trust-boundary rejection of an ineligible model selection. */
public class ActionIneligibleException extends AgentContractException {

    private final ActionEligibilityReasonCode reasonCode;

    public ActionIneligibleException(ActionEligibilityReasonCode reason) {
        super("ACTION_INELIGIBLE: " + reason.name());
        this.reasonCode = reason;
    }

    public ActionEligibilityReasonCode reasonCode() {
        return reasonCode;
    }
}
