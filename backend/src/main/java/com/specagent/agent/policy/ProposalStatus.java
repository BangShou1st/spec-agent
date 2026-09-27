package com.specagent.agent.policy;

/**
 * 文件名:ProposalStatus.java
 *
 * 用途:动作提案的生命周期状态枚举。它与节点的知识状态(Node
 * knowledge state)是两回事——提案跟踪的是审批工作流,不是内容置信度。
 *
 * 协作:作为 {@link AgentProposal} 的状态字段持久化;
 * fromCode 解析数据库中的状态码,空值直接拒绝。
 */
public enum ProposalStatus {
    PROPOSED,
    ACCEPTED,
    MODIFIED,
    REJECTED,
    EXPIRED;

    public String code() {
        return name();
    }

    public static ProposalStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Proposal status must not be blank");
        }
        return ProposalStatus.valueOf(code);
    }
}
