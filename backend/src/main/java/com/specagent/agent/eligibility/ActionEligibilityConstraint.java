package com.specagent.agent.eligibility;

import java.util.List;

/** Eligibility result for one action family. */
public record ActionEligibilityConstraint(
        boolean eligible,
        List<ActionEligibilityReasonCode> reasonCodes) {

    public ActionEligibilityConstraint {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public static ActionEligibilityConstraint allow() {
        return new ActionEligibilityConstraint(true, List.of());
    }

    public static ActionEligibilityConstraint deny(ActionEligibilityReasonCode... reasons) {
        return new ActionEligibilityConstraint(false, List.of(reasons));
    }
}
