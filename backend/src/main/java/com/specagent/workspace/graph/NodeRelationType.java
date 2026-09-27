package com.specagent.workspace.graph;

/**
 * 文件名:NodeRelationType.java
 *
 * 用途:节点间语义关系的类型词表。语义关系与可见的续写 lineage
 * 分开存储,绝不渲染成默认画布边。模型推断的关系只能以 Advisor 提案
 * 的形式进入;仅有置信度绝不足以把推断关系变成持久事实。
 */
public enum NodeRelationType {

    RELATED_TO("RELATED_TO"),
    DEPENDS_ON("DEPENDS_ON"),
    DERIVED_FROM("DERIVED_FROM"),
    CONFLICTS_WITH("CONFLICTS_WITH"),
    SUPPORTS("SUPPORTS");

    private final String code;

    NodeRelationType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeRelationType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Relation type must not be null");
        }
        for (NodeRelationType type : values()) {
            if (type.code.equals(code.trim().toUpperCase())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown relation type: " + code);
    }
}
