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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * 绝不直接依赖 SecretStore。仅未配置引用可匿名,已配置引用不可解析时拒绝网络。
 * 存在的引用生成 "Bearer &lt;token&gt;" 请求头,明文
 * 绝不出现在任何消息或投影中。
 */
@ExtendWith(MockitoExtension.class)
class McpCredentialResolverAdapterTest {

    @Mock
    private SecretStore secretStore;

    @Mock
    private McpClientFactory clientFactory;

    private McpConnectionTarget target(String credentialRef) {
        currentTarget = new McpConnectionTarget(UUID.randomUUID(), "conn-seam", true,
                "https://mcp.example/seam", credentialRef);
        return currentTarget;
    }

    private McpConnectionRuntime runtime(com.specagent.mcp.runtime.McpCredentialResolver resolver) {
        var lookup=mock(com.specagent.mcp.runtime.McpConnectionLookupPort.class);
        org.mockito.Mockito.lenient().when(lookup.findByRowId(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> java.util.Optional.of(currentTarget));
        return new McpConnectionRuntime(clientFactory, resolver, lookup);
    }

    private McpConnectionTarget currentTarget;

    @Test
    void blankOrNullReferenceResolvesToUnauthenticatedWithoutTouchingTheStore() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull(null)).isNull();
        assertThat(resolver.resolveOrNull("  ")).isNull();

        verifyNoInteractions(secretStore);
    }

    @Test
    void vanishedCredentialRowFailsClosed() {
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThatThrownBy(()->resolver.resolveOrNull("cred:gone"))
                .isInstanceOf(com.specagent.mcp.transport.McpTransportException.class)
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE");

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
        McpConnectionRuntime runtime = runtime(resolver);
        when(clientFactory.open(eq("https://mcp.example/seam"), eq(Map.of()),
                eq("Bearer plain-token"))).thenReturn(mock(McpClientFactory.Session.class));

        runtime.openSession(target("cred:live"));

        verify(clientFactory).open("https://mcp.example/seam", Map.of(), "Bearer plain-token");
    }

    @Test
    void runtimeDoesNotOpenSessionWhenCredentialRowIsGone() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpConnectionRuntime runtime = runtime(resolver);
        assertThatThrownBy(()->runtime.openSession(target("cred:gone")))
                .isInstanceOf(com.specagent.mcp.transport.McpTransportException.class)
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE");
        verifyNoInteractions(clientFactory);
    }
    @Test void runtimeRefusesAnEmptyTokenReturnedByAnyResolverImplementation() {
        var resolver=mock(com.specagent.mcp.runtime.McpCredentialResolver.class);
        when(resolver.resolveOrNull("cred:empty")).thenReturn(" ");
        assertThatThrownBy(()->runtime(resolver).openSession(target("cred:empty")))
                .isInstanceOf(com.specagent.mcp.transport.McpTransportException.class)
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE");
        verifyNoInteractions(clientFactory);
    }
    @Test void resolverDoesNotExposeDecryptionOrStoreExceptionMessages() {
        when(secretStore.maskedSuffix("cred:bad")).thenReturn("****");
        when(secretStore.resolve("cred:bad")).thenThrow(new IllegalStateException("secret-value-in-unsafe-error"));
        assertThatThrownBy(()->new McpCredentialResolverAdapter(secretStore).resolveOrNull("cred:bad"))
                .isInstanceOf(com.specagent.mcp.transport.McpTransportException.class)
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE").hasNoCause();
    }
    @Test void authenticationIntentSurvivesMissingReferenceAndRefusesAnonymousSession() {
        var resolver=mock(com.specagent.mcp.runtime.McpCredentialResolver.class);
        var target=new McpConnectionTarget(UUID.randomUUID(),"configured-but-missing",true,"https://mcp.example/seam",null,"BEARER",2);
        currentTarget = target;
        assertThatThrownBy(()->runtime(resolver).openSession(target))
                .isInstanceOf(com.specagent.mcp.transport.McpTransportException.class)
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE");
        verifyNoInteractions(clientFactory,resolver);
    }
}
