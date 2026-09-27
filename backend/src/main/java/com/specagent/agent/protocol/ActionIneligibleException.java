package com.specagent.agent.protocol;

import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.protocol.AgentContractException;

/**
 * 文件名:ActionIneligibleException.java
 *
 * 用途:信任边界上的 fail-closed 拒绝异常——当 Brain 提出的动作家族
 * 不在 {@link ActionEligibility} 允许列表中时抛出,携带机器可读原因码。
 */
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
