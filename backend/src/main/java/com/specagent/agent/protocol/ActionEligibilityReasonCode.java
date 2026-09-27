package com.specagent.agent.protocol;

/**
 * 文件名:ActionEligibilityReasonCode.java
 *
 * 用途:确定性可用性判定(reasoning 不参与)给出的机器可读原因码,
 * 用于解释某个动作家族为何被允许或拒绝,便于审计与前端展示。
 */
public enum ActionEligibilityReasonCode {
    ANSWER_ALREADY_DURABLE,
    NO_NEW_DURABLE_UNIT,
    CONFIRMED_STATE_ALREADY_DURABLE,
    MISSING_TYPED_PERSISTENCE_INTENT,
    UNRESOLVED_BLOCKER,
    NO_PENDING_DEPENDENCY,
    CAPABILITY_NOT_VISIBLE,
    UNGROUNDED_CAPABILITY_ARGUMENT,
    RESOLVED_BLOCKER,
    GRAPH_MUTATION_NOT_ALLOWED,
    ELIGIBILITY_VERSION_MISMATCH,
    ELIGIBILITY_BASIS_MISMATCH,
    FAMILY_NOT_ELIGIBLE
}
