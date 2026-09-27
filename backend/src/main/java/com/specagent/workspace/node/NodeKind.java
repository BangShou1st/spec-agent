package com.specagent.workspace.node;

/**
 * 文件名:NodeKind.java
 *
 * 用途:工作区节点的外层稳定分类。kind 集合刻意保持小而稳定;
 * 产品层面的差异放进 {@code subtype} 和 {@code content},而不是新增
 * kind。早于通用工作区模型的遗留行按 {@code INTERACTION} 解释。
 */
public enum NodeKind {

    KNOWLEDGE("KNOWLEDGE"),
    INTERACTION("INTERACTION"),
    RESOURCE("RESOURCE"),
    ARTIFACT("ARTIFACT");

    private final String code;

    NodeKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeKind fromCode(String code) {
        if (code == null) {
            return INTERACTION;
        }
        for (NodeKind kind : values()) {
            if (kind.code.equals(code.trim().toUpperCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown node kind: " + code);
    }
}
