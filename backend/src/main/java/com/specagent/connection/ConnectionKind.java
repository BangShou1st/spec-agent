package com.specagent.connection;

/**
 * 文件名:ConnectionKind.java
 *
 * 用途:Connection 的类型枚举,区分两类外部集成。
 *
 * {@code SYSTEM_SUPPORTED} 是产品托管、有既定扩展点的集成(例如 GitHub);
 * {@code CUSTOM_MCP} 是用户自填的远程 MCP 服务器。Connection 是产品概念,
 * MCP 是协议实现——两者永远不会合并成同一个类。
 */
public enum ConnectionKind {

    SYSTEM_SUPPORTED("SYSTEM_SUPPORTED"),
    CUSTOM_MCP("CUSTOM_MCP");

    private final String code;

    ConnectionKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ConnectionKind fromCode(String code) {
        for (ConnectionKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown connection kind: " + code);
    }
}