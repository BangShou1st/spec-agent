package com.specagent.mcp.transport;

/**
 * 文件名:McpTransportException.java
 *
 * 用途:MCP 传输/握手/发现问题的带类型失败。允许引用 SDK 异常类名用于诊断,
 * 但 Provider 的异常与堆栈细节永远不会到达模型或用户。
 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}