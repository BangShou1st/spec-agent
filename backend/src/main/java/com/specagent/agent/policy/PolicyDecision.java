package com.specagent.agent.policy;

/**
 * 文件名:PolicyDecision.java
 *
 * 用途:Advisor 策略引擎对动作提案的评估结果:允许自动执行、
 * 要求用户确认,还是直接拒绝。
 *
 * 模型置信度绝不参与 autoExecute / requiresConfirmation 的判定,
 * 它只作为信息性信号携带。
 *
 * 协作:由 AdvisorPolicyEngine.evaluate 产出,供决策循环与
 * ProposalAcceptanceService 消费。
 */
public record PolicyDecision(MutationClass classification,
                             boolean autoExecute,
                             boolean requiresConfirmation,
                             String denyReason) {

    public static PolicyDecision autoExecute(MutationClass classification) {
        return new PolicyDecision(classification, true, false, null);
    }

    public static PolicyDecision requireConfirmation(MutationClass classification) {
        return new PolicyDecision(classification, false, true, null);
    }

    public static PolicyDecision deny(MutationClass classification, String reason) {
        return new PolicyDecision(classification, false, false, reason);
    }
}
