package com.specagent.agent.action;

import java.util.UUID;

/**
 * 文件名:ActionExecutionContext.java
 *
 * 用途:执行或校验一个动作提案所需的 Runtime 上下文。携带持久化身份
 * (run、项目、路由、上下文快照、锚点节点)与当前选择/自由文本,
 * 供存活检查和动作落地使用。
 *
 * 协作:由决策循环在消费 Brain 响应时构造,随提案一起传给
 * StaleContextChecker、ActionExecutor 等执行链路。
 */
public record ActionExecutionContext(UUID runId,
                                     UUID projectId,
                                     UUID routeId,
                                     UUID contextSnapshotId,
                                     UUID anchorNodeId,
                                     UUID selectedOptionId,
                                     String freeText) {
}
