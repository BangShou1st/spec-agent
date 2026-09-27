package com.specagent.agent.runevent;

/**
 * 文件名:AgentRunPhase.java
 *
 * 用途:写入 {@code agent_run_events} 的公开运行阶段枚举。
 *
 * 约束:UI 的进度文案必须从这些真实阶段推导,绝不允许凭空编造。
 */
public enum AgentRunPhase {
    CREATED,
    SNAPSHOT_BUILT,
    STATE_UPDATING,
    STATE_UPDATED,
    DECIDING,
    PROPOSAL_CREATED,
    ARTIFACT_GENERATING,
    AWAITING_APPROVAL,
    EXECUTING,
    WAITING_USER,
    COMPLETED,
    FAILED,
    STALE;

    public String code() {
        return name();
    }

    public static AgentRunPhase fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent run phase must not be blank");
        }
        return AgentRunPhase.valueOf(code);
    }
}
