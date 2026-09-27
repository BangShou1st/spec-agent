package com.specagent.model.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:ProviderUrlSecurityTest.java
 *
 * 测试目标:验证自定义 Provider Base URL 的安全校验与规范化:HTTPS 公网/私网地址合法;
 * HTTP 仅允许环回地址(localhost/127.0.0.1/[::1]);公网 HTTP、携带 userinfo/query/fragment、
 * 非 http(s) scheme、云元数据链路本地地址(169.254.169.254)一律拒绝;
 * canonicalEndpoint 按协议格式正确拼接端点路径。
 */
class ProviderUrlSecurityTest {
    @Test void httpsPublicValid() {
        assertThat(ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://api.example.com/v1"))
                .isEqualTo("https://api.example.com/v1");
    }

    @Test void httpsPrivateValid() {
        assertThat(ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://192.168.1.10/v1"))
                .isEqualTo("https://192.168.1.10/v1");
    }

    @Test void httpLoopbackValid() {
        assertThat(ProviderUrlSecurity.validateAndNormalizeBaseUrl("http://localhost:11434/v1"))
                .isEqualTo("http://localhost:11434/v1");
        assertThat(ProviderUrlSecurity.validateAndNormalizeBaseUrl("http://127.0.0.1:8000/v1/"))
                .isEqualTo("http://127.0.0.1:8000/v1");
        assertThat(ProviderUrlSecurity.validateAndNormalizeBaseUrl("http://[::1]/v1"))
                .isEqualTo("http://[::1]/v1");
    }

    @Test void httpPublicRejected() {
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("http://api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void userinfoQueryFragmentRejected() {
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://user:pass@api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://api.example.com/v1?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://api.example.com/v1#frag"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void badSchemeRejected() {
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("ftp://api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void metadataLinkLocalRejected() {
        assertThatThrownBy(() -> ProviderUrlSecurity.validateAndNormalizeBaseUrl("https://169.254.169.254/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalEndpointAppendsOnce() {
        assertThat(ProviderUrlSecurity.canonicalEndpoint("https://host/v1", CustomApiFormat.CHAT_COMPLETIONS))
                .isEqualTo("https://host/v1/chat/completions");
        assertThat(ProviderUrlSecurity.canonicalEndpoint("https://host/v1", CustomApiFormat.RESPONSES))
                .isEqualTo("https://host/v1/responses");
        assertThat(ProviderUrlSecurity.canonicalEndpoint("https://host/v1", CustomApiFormat.ANTHROPIC_MESSAGES))
                .isEqualTo("https://host/v1/messages");
    }
}
