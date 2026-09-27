package com.specagent.mcp.provider;

/**
 * 文件名:McpConnectionCommandException.java
 *
 * 用途:针对连接的 MCP 资产访问失败(连接不存在、对 Agent 不可见、资源/提示
 * 未暴露)时的带类型失败。由 MCP 模块持有,使运行时无需 import 连接服务包;
 * API 边界会把它映射为与连接侧命令拒绝完全一致的公开契约
 * (400 CONNECTION_COMMAND_REJECTED)。Provider/底层栈的原始细节永远不会
 * 泄露给调用方。
 */
public class McpConnectionCommandException extends RuntimeException {

    public McpConnectionCommandException(String message) {
        super(message);
    }
}
