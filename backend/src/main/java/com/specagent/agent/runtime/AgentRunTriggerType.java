package com.specagent.agent.runtime;

/**
 * 文件名:AgentRunTriggerType.java
 *
 * 用途:触发 AgentRun 的操作类型枚举。
 *
 * 这些是"操作机制"而非业务领域,描述的是哪种用户操作引发了本次 run,
 * 从而让 runtime 保持领域中立。
 */
public enum AgentRunTriggerType {
    INITIAL_REQUIREMENT,
    ANSWER_NODE,
    REGENERATE_NODE,
    FORK_NODE,
    RESTORE_ROUTE,
    ARCHIVE_ROUTE,
    DELETE_ROUTE,
    GENERATE_SPEC,
    DECISION_CYCLE,
    ANSWER_CYCLE,
    NODE_QUERY,
    /**
     * 在同一个外部观察边界内,对上一个 run 的自治续跑。
     * 只能由续跑协调器(Slice 2+)创建,绝不允许由面向用户的 API 直接创建。
     * Slice 0 先接通路由,执行逻辑随后到位。
     */
    CONTINUE_CYCLE;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentRunTriggerType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Agent run trigger type code must not be null");
        }
        return AgentRunTriggerType.valueOf(code.toUpperCase());
    }
}
