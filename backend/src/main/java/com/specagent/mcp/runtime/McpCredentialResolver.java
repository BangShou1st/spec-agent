package com.specagent.mcp.runtime;

/**
 * 文件名:McpCredentialResolver.java
 *
 * 用途:MCP 运行时持有的凭据解析端口(seam):把不透明的凭据引用解析成明文
 * token 交给传输层;仅未配置引用时返回 {@code null};已配置引用解析失败须拒绝。
 * 实现位于连接侧;
 * 明文绝不能出现在描述符、追踪、能力结果、异常消息、API 响应或日志中。
 */
public interface McpCredentialResolver {

    /** 未配置引用可返回 null;已配置引用不能回退匿名。 */
    String resolveOrNull(String credentialRef);
}
