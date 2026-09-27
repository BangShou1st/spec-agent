package com.specagent.mcp.runtime;

/**
 * 文件名:McpCredentialResolver.java
 *
 * 用途:MCP 运行时持有的凭据解析端口(seam):把不透明的凭据引用解析成明文
 * token 交给传输层;当引用缺失/为空,或凭据行已不存在时返回 {@code null}
 * (沿用"凭据行已消失——视为未认证"的历史语义)。实现位于连接侧;
 * 明文绝不能出现在描述符、追踪、能力结果、异常消息、API 响应或日志中。
 */
public interface McpCredentialResolver {

    /** 已存在的引用返回明文 token,否则返回 null(未认证)。 */
    String resolveOrNull(String credentialRef);
}
