package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionProposal;

/**
 * 文件名:ActionExecutor.java
 *
 * 用途:动作执行器端口,负责把通过校验的动作提案落地到图上。
 * 执行器拥有身份分配和不变量强制;提案只携带内容,绝不携带
 * Runtime 持有的 ID。
 *
 * 协作:由决策循环在资格门禁与 stale 检查全部通过后调用。
 * Stage B 实现了完整的九个动作族分发;尚不能执行的动作族
 * (INVOKE_CAPABILITY、GENERATE_ARTIFACT)返回显式的"不支持"结果,
 * 而不是静默成功。
 */
public interface ActionExecutor {

    /**
     * 校验并执行给定提案。当提案的基础上下文已与当前权威图状态
     * 不再匹配时,抛出 {@link StaleProposalException}。
     */
    ActionResult execute(ActionProposal proposal, ActionExecutionContext context);
}
