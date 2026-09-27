package com.specagent.workspace.node;

/**
 * 文件名:KnowledgeStatus.java
 *
 * 用途:claim 类节点内容的知识状态。并非所有 kind 都使用它:
 * 交互节点和资源节点不携带知识状态。
 *
 * 它与操作/进度状态(存放在 {@code AgentRun} 上)以及路线生命周期
 * 状态刻意保持区分。
 */
public enum KnowledgeStatus {

    PROPOSED("PROPOSED"),
    CONFIRMED("CONFIRMED"),
    CHALLENGED("CHALLENGED"),
    SUPERSEDED("SUPERSEDED");

    private final String code;

    KnowledgeStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static KnowledgeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (KnowledgeStatus status : values()) {
            if (status.code.equals(code.trim().toUpperCase())) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown knowledge status: " + code);
    }
}
