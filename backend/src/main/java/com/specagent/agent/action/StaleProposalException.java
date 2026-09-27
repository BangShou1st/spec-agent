package com.specagent.agent.action;

/**
 * 文件名:StaleProposalException.java
 *
 * 用途:动作提案的基础上下文已与当前权威图状态不再匹配时抛出。
 * 执行器必须拒绝过期的提案,调用方应从新的 snapshot 重新发起 run,
 * 而不是静默 rebase 到更新的状态上。
 *
 * 协作:由 StaleContextChecker、AgentGraphMutationService 等
 * 前置条件校验点抛出。
 */
public class StaleProposalException extends RuntimeException {

    public StaleProposalException(String message) {
        super(message);
    }
}
