package com.specagent.connection.credentials;

import com.specagent.mcp.runtime.McpCredentialResolver;
import org.springframework.stereotype.Component;

/**
 * 文件名:McpCredentialResolverAdapter.java
 *
 * 用途:在 connection 包的 {@link SecretStore} 之上,对 MCP 侧的
 * {@link McpCredentialResolver} 做薄实现,在 MCP 会话建立时解析凭据明文。
 *
 * 未配置引用表示显式匿名连接;配置的引用无法解析时失败关闭。明文 token 只交给
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
        try {
            if (secretStore.maskedSuffix(credentialRef) == null) throw unavailable();
            String token=secretStore.resolve(credentialRef);
            if(token==null || token.isBlank()) throw unavailable();
            return token;
        } catch(RuntimeException refused) {
            // Store/crypto/provider messages may contain secret material; never forward them.
            throw unavailable();
        }
    }
    private static com.specagent.mcp.transport.McpTransportException unavailable() {
        return new com.specagent.mcp.transport.McpTransportException("MCP_CREDENTIAL_UNAVAILABLE");
    }
}
