package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:ActionEligibilityConstraint.java
 *
 * 用途:单个动作家族的可用性判定结果——是否允许(eligible)以及
 * 不允许时的机器可读原因码列表。与 {@link ActionEligibility} 中的
 * constraints 映射配合使用,每个家族必须有一条对应记录。
 */
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
