package com.specagent.agent.protocol;

/**
 * 文件名:DecisionBudget.java
 *
 * 用途:单个决策周期的循环预算,由 Runtime 所有并下发给 Brain。
 *
 * 约束:Brain 必须在该预算内行动;Runtime 会独立地强制执行预算,
 * 不依赖 Brain 自律。
 */
public record DecisionBudget(int maxModelCalls) {
}
