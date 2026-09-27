package com.specagent.connection;

import com.specagent.connection.credentials.McpCredentialResolverAdapter;
import com.specagent.connection.credentials.SecretStore;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.transport.McpClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:McpCredentialResolverAdapterTest.java
 *
 * 测试目标:验证 Issue #14 凭据接缝回归——MCP 运行时通过 MCP 自有的
 * {@link com.specagent.mcp.runtime.McpCredentialResolver} 端口解析连接凭据,
 * 绝不直接依赖 SecretStore。空引用与凭据行消失时保持未认证(auth header
 * 为 null),存在的引用生成历史的 "Bearer &lt;token&gt;" 请求头,明文
 * 绝不出现在任何消息或投影中。
 */
@ExtendWith(MockitoExtension.class)
class McpCredentialResolverAdapterTest {

    @Mock
    private SecretStore secretStore;

    @Mock
    private McpClientFactory clientFactory;

    private McpConnectionTarget target(String credentialRef) {
        return new McpConnectionTarget(UUID.randomUUID(), "conn-seam", true,
                "https://mcp.example/seam", credentialRef);
    }

    @Test
    void blankOrNullReferenceResolvesToUnauthenticatedWithoutTouchingTheStore() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull(null)).isNull();
        assertThat(resolver.resolveOrNull("  ")).isNull();

        verifyNoInteractions(secretStore);
    }

    @Test
    void vanishedCredentialRowIsTreatedAsUnauthenticated() {
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull("cred:gone")).isNull();

        verify(secretStore, never()).resolve("cred:gone");
    }

    @Test
    void existingCredentialRowResolvesToThePlaintextToken() {
        when(secretStore.maskedSuffix("cred:live")).thenReturn("****-1234");
        when(secretStore.resolve("cred:live")).thenReturn("plain-token");
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull("cred:live")).isEqualTo("plain-token");
    }

    @Test
    void runtimeBuildsTheBearerHeaderOnlyAtTheTransportSeam() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);
        when(secretStore.maskedSuffix("cred:live")).thenReturn("****-1234");
        when(secretStore.resolve("cred:live")).thenReturn("plain-token");
        McpConnectionRuntime runtime = new McpConnectionRuntime(clientFactory, resolver);
        when(clientFactory.open(eq("https://mcp.example/seam"), eq(Map.of()),
                eq("Bearer plain-token"))).thenReturn(mock(McpClientFactory.Session.class));

        runtime.openSession(target("cred:live"));

        verify(clientFactory).open("https://mcp.example/seam", Map.of(), "Bearer plain-token");
    }

    @Test
    void runtimeOpensUnauthenticatedSessionWhenCredentialRowIsGone() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpConnectionRuntime runtime = new McpConnectionRuntime(clientFactory, resolver);
        when(clientFactory.open(eq("https://mcp.example/seam"), eq(Map.of()),
                eq(null))).thenReturn(mock(McpClientFactory.Session.class));

        runtime.openSession(target("cred:gone"));

        verify(clientFactory).open("https://mcp.example/seam", Map.of(), null);
    }
}
