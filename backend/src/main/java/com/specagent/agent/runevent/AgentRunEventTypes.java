package com.specagent.agent.runevent;

/**
 * 文件名:AgentRunEventTypes.java
 *
 * 用途:终态信号(terminal-outcome)共享的持久化运行事件协议常量。
 *
 * 约束:节点查询执行、续跑协调器、回答/决策周期以及查询结果视图
 * 都读取同一批事件行;这些字符串值是冻结的 Runtime 证据——除非连带
 * 迁移既有数据行,否则绝不能重命名。
 */
public final class AgentRunEventTypes {

    /** 决策周期已给出回答并产出用户可见的消息。 */
    public static final String RESPOND_MESSAGE_EVENT = "RESPOND_MESSAGE";

    /** 策略拒绝了提案的动作,未产生持久化变更。 */
    public static final String POLICY_DENIED_EVENT = "POLICY_DENIED";

    /** 变更提案永远无法变为可执行状态,因此未保留任何提案。 */
    public static final String MUTATION_NOT_CONFIRMABLE_EVENT = "MUTATION_NOT_CONFIRMABLE";

    private AgentRunEventTypes() {
    }
}
