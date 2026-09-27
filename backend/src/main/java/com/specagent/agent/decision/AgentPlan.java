package com.specagent.agent.decision;

import com.specagent.agent.decision.AgentAction;

/**
 * 文件名:AgentPlan.java
 *
 * 用途:Agent 规划出的下一步,由一个封闭集合内的动作以及背后的
 * 推理理由(rationale)组成。
 */
public record AgentPlan(
        AgentAction nextAction,
        String rationale
) {
    public AgentPlan {
        if (nextAction == null) {
            throw new IllegalArgumentException("nextAction is required");
        }
        rationale = rationale == null ? "" : rationale;
    }
}