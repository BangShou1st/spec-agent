package com.specagent.mcp.runtime;

import java.util.UUID;

/**
 * 文件名:McpConnectionTarget.java
 *
 * 用途:已保存 Connection 的、由 MCP 模块持有的投影:只包含协议层允许看到的
 * 内容——内部行 id(缓存/会话键)、对外连接 id(用于能力 id 和错误消息)、
 * Agent 可见性、安全的 serverUrl,以及不透明的凭据引用(绝不是明文密钥)。
 * 连接领域对象本身绝不进入 {@code com.specagent.mcp}。
 *
 * @param rowId        内部行 id,用作缓存/会话键
 * @param connectionId 对外的产品级连接 id
 * @param agentVisible 是否对 Agent 可见
 * @param serverUrl    Server 地址
 * @param credentialRef 凭据引用(不透明,非明文密钥)
 */
public record McpConnectionTarget(
        UUID rowId,
        String connectionId,
        boolean agentVisible,
        String serverUrl,
        String credentialRef) {

    /** 绝不允许诊断输出回显凭据引用。 */
    @Override
    public String toString() {
        return "McpConnectionTarget[connectionId=" + connectionId
                + ", agentVisible=" + agentVisible
                + ", serverUrl=" + serverUrl + "]";
    }
}
