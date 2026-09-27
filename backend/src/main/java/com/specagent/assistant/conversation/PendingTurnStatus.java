package com.specagent.assistant.conversation;

/**
 * 文件名:PendingTurnStatus.java
 *
 * 用途:可持久化 Steer 轮次的生命周期状态。
 *
 * 角色:conversation 包的状态枚举。PENDING(待认领)→ CLAIMED
 * (已认领)→ CONSUMED(已由后继 Run 消费)/ DISCARDED(已丢弃);
 * 只有 PENDING/CLAIMED 算作"未决"状态。
 */
public enum PendingTurnStatus {
    PENDING,
    CLAIMED,
    CONSUMED,
    DISCARDED;

    public static PendingTurnStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Pending turn status must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }

    public boolean isUnresolved() {
        return this == PENDING || this == CLAIMED;
    }
}
