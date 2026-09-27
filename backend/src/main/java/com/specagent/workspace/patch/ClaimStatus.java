package com.specagent.workspace.patch;

/**
 * 文件名:ClaimStatus.java
 *
 * 用途:需求 claim 的"落地"状态。没有来源引用支撑的模型输出必须
 * 被标记为 {@code ASSUMED}、{@code UNRESOLVED} 或 {@code REJECTED},
 * 绝不能无来源地标记为 {@code CONFIRMED}。
 */
public enum ClaimStatus {
    CONFIRMED,
    ASSUMED,
    UNRESOLVED,
    REJECTED;

    public String code() {
        return name().toLowerCase();
    }

    public static ClaimStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Claim status code must not be null");
        }
        return ClaimStatus.valueOf(code.toUpperCase());
    }
}
