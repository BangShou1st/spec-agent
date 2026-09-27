package com.specagent.agent.action;

/**
 * 文件名:DecisionLoopBudget.java
 *
 * 用途:决策循环的预算控制。限制单次 agent run 内的最大决策步数
 * 与每步最大模型调用次数,并检测重复或无进展的动作。每个决策周期
 * 扣减剩余预算;预算耗尽后循环必须停止,防止模型陷入无休止的循环。
 *
 * 协作:defaultBudget() 提供默认预算(10 步、每步 2 次调用),
 * 随请求信封下发,由决策循环逐周期消费。
 */
public record DecisionLoopBudget(int maxDecisionSteps,
                                 int maxModelCallsPerStep) {

    public static DecisionLoopBudget defaultBudget() {
        return new DecisionLoopBudget(10, 2);
    }
}
