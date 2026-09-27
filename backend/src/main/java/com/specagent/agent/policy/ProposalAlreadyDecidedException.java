package com.specagent.agent.policy;

/**
 * 文件名:ProposalAlreadyDecidedException.java
 *
 * 用途:提案的终态流转在单胜者竞争中落败时抛出:该提案已经通过
 * 另一个决策(接受/拒绝/过期)离开了 PROPOSED 状态。
 *
 * 这是预期的并发结果,不是内部故障——落败方必须以确定性的业务错误
 * 呈现(映射为 {@code 409 PROPOSAL_ALREADY_DECIDED}),绝不能是 500、
 * SQL 约束冲突或静默的假成功。
 *
 * 协作:由 AgentProposalService 的生命周期流转方法抛出。
 */
public class ProposalAlreadyDecidedException extends RuntimeException {

    private final String currentStatus;

    public ProposalAlreadyDecidedException(String currentStatus) {
        super("Proposal has already been decided: " + currentStatus);
        this.currentStatus = currentStatus;
    }

    /** 胜者留下的已提交状态,例如 ACCEPTED / REJECTED / EXPIRED。 */
    public String currentStatus() {
        return currentStatus;
    }
}
