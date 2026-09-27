package com.specagent.workspace.node;

/**
 * 文件名:NodeAuthorKind.java
 *
 * 用途:标记节点的创建者。出处由运行时权威记录,绝不从模型输出
 * 推断。
 */
public enum NodeAuthorKind {

    USER("USER"),
    AGENT("AGENT"),
    RUNTIME("RUNTIME");

    private final String code;

    NodeAuthorKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeAuthorKind fromCode(String code) {
        if (code == null) {
            return AGENT;
        }
        for (NodeAuthorKind kind : values()) {
            if (kind.code.equals(code.trim().toUpperCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown node author kind: " + code);
    }
}
