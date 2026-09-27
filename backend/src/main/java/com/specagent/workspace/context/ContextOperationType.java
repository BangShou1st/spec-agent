package com.specagent.workspace.context;

/**
 * 文件名:ContextOperationType.java
 *
 * 用途:标记一份上下文快照是为哪类操作构建的(正常作答、重建、分支、恢复、
 * 生成规格、初始、节点查询)。只表达操作机制,绝不编码业务领域语义。
 */
public enum ContextOperationType {
    NORMAL,
    REGENERATE,
    FORK,
    RESTORE,
    GENERATE_SPEC,
    INITIAL,
    NODE_QUERY;

    public String code() {
        return name().toLowerCase();
    }

    public static ContextOperationType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Context operation type code must not be null");
        }
        return ContextOperationType.valueOf(code.toUpperCase());
    }
}
