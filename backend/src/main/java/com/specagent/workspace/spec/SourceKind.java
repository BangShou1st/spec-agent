package com.specagent.workspace.spec;

/**
 * 文件名:SourceKind.java
 *
 * 用途:规格 claim 溯源目标的运行时记录种类(节点、回答、补丁、上下文快照、
 * route)。每条已确认的规格 claim 必须引用至少一条来源记录;这是领域中立的
 * 溯源枚举,不是业务领域分类。
 */
public enum SourceKind {
    NODE,
    ANSWER,
    PATCH,
    CONTEXT,
    ROUTE;

    public String code() {
        return name().toLowerCase();
    }

    public static SourceKind fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Source kind code must not be null");
        }
        return SourceKind.valueOf(code.toUpperCase());
    }
}
