package com.specagent.assistant.conversation;

/**
 * 文件名:GlobalAssistantRunStatus.java
 *
 * 用途:极简的全局助手 Run 生命周期(冻结契约)。
 *
 * 角色:conversation 包的 Run 状态枚举。CREATED/RUNNING 为活跃态,
 * COMPLETED/FAILED/CANCELLED 为终态;{@code cancel_requested_at} 只是
 * 协作式取消信号列,永远不是一种状态。不存在 CANCELLING/WAITING/
 * USER_INPUT_REQUIRED 等持久化状态:澄清类交互通过正常终态结束当前 Run。
 */
public enum GlobalAssistantRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String code() {
        return name();
    }

    public static GlobalAssistantRunStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Run status must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }

    public boolean isActive() {
        return this == CREATED || this == RUNNING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
