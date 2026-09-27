package com.specagent.connection.credentials;

import com.specagent.mcp.runtime.McpCredentialResolver;
import org.springframework.stereotype.Component;

/**
 * 文件名:McpCredentialResolverAdapter.java
 *
 * 用途:在 connection 包的 {@link SecretStore} 之上,对 MCP 侧的
 * {@link McpCredentialResolver} 做薄实现,在 MCP 会话建立时解析凭据明文。
 *
 * 完整保留 MCP 运行时的既有语义:空引用或凭据行已不存在时解析为
 * {@code null}(会话以未认证方式继续),而不是直接失败。明文 token 只交给
 * 协议 transport,绝不写入日志、trace、描述符、capability 结果、
 * 异常或 API 响应。
 */
@Component
public class McpCredentialResolverAdapter implements McpCredentialResolver {

    private final SecretStore secretStore;

    public McpCredentialResolverAdapter(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    @Override
    public String resolveOrNull(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        if (secretStore.maskedSuffix(credentialRef) == null) {
            return null; // 凭据行已不存在——按未认证处理
        }
        return secretStore.resolve(credentialRef);
    }
}
