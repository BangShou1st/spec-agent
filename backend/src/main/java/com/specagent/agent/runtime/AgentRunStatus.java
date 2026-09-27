package com.specagent.agent.runtime;

/**
 * 文件名:AgentRunStatus.java
 *
 * 用途:单次受控 Agent 执行(AgentRun)的生命周期状态枚举。
 * 状态大致沿"创建 → 运行 → 上下文冻结 → 调用模型 → 反思校验 → 持久化产出
 * → 完成/失败"推进,COMPLETED 与 FAILED 为终态。
 */
public enum AgentRunStatus {
    CREATED,
    RUNNING,
    CONTEXT_BUILT,
    MODEL_CALLED,
    REFLECTED,
    PERSISTED,
    COMPLETED,
    FAILED;

    public String code() {
        return name().toLowerCase();
    }

    /** COMPLETED 与 FAILED 是终态:不可被任何执行器覆盖。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public static AgentRunStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Agent run status code must not be null");
        }
        return AgentRunStatus.valueOf(code.toUpperCase());
    }
}
