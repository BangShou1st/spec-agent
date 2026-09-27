package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProviderUrlSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:CrossOriginCredentialGuardTest.java
 *
 * 测试目标:凭据复用的来源边界(R1)。使用本机两个独立的 HTTP 接收端
 * 与合成密钥证明:已保存到来源 A 的密钥绝不会到达来源 B——
 * - 统一 provider 的 discover(probe)路径:同来源复用、跨来源阻断、
 *   显式新密钥、显式清空、默认端口归一化;
 * - 旧单例 Custom 接口的 discover 与 save 路径同样守卫;
 * - 修改 base URL 的保存路径必须显式决定密钥,不得静默沿用。
 * 全部使用合成密钥,接收端只绑定回环地址。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CrossOriginCredentialGuardTest {

    private static final String KEY_A = "synthetic-key-for-origin-a";
    private static final String KEY_B = "synthetic-key-for-origin-b";

    @Autowired ModelProvidersService providers;
    @Autowired CustomProviderSettingsService custom;

    /** 一个可记录收到的 Authorization 头与请求数的回环接收端。 */
    private static class Receiver {
        HttpServer server;
        String baseUrl;
        final AtomicReference<String> auth = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger();

        Receiver(String context) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = new ObjectMapper().writeValueAsBytes(Map.of("data",
                        List.of(Map.of("id", "m1"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, context);
                t.setDaemon(true);
                return t;
            }));
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void stop() {
            server.stop(0);
            if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
        }
    }

    private Receiver a;
    private Receiver b;

    @BeforeEach
    void startReceivers() throws Exception {
        a = new Receiver("receiver-a");
        b = new Receiver("receiver-b");
    }

    @AfterEach
    void stopReceivers() {
        a.stop();
        b.stop();
    }

    private ModelProviderRecord createProviderAt(String baseUrl, String apiKey) {
        return providers.create(new ModelProvidersService.Patch(
                null, "Origin guard " + UUID.randomUUID(), "CHAT_COMPLETIONS",
                baseUrl, apiKey, "m1", null));
    }

    // ---------------- 统一 provider:discover(probe)路径 ----------------

    @Test
    void sameOriginReusesStoredKey() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        providers.discover(saved.id(), null, a.baseUrl, null);
        assertThat(a.auth.get()).isEqualTo("Bearer " + KEY_A);
    }

    @Test
    void crossOriginProbeNeverReceivesStoredKey() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        assertThatThrownBy(() -> providers.discover(saved.id(), null, b.baseUrl, null))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("different origin");
        // 探测请求根本没有发出:旧密钥与新目标之间零接触
        assertThat(b.requests.get()).isZero();
        assertThat(b.auth.get()).isNull();
    }

    @Test
    void crossOriginProbeWithExplicitNewKeyUsesOnlyNewKey() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        providers.discover(saved.id(), "CHAT_COMPLETIONS", b.baseUrl, KEY_B);
        assertThat(b.auth.get()).isEqualTo("Bearer " + KEY_B);
        assertThat(a.auth.get()).isNull();
    }

    @Test
    void crossOriginProbeWithExplicitEmptyKeyMeansUnauthenticated() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        providers.discover(saved.id(), "CHAT_COMPLETIONS", b.baseUrl, "");
        assertThat(b.requests.get()).isPositive();
        assertThat(b.auth.get()).isNull();
    }

    @Test
    void sameOriginPathChangeStillReusesKey() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        String deeperPath = a.baseUrl + "/";
        providers.discover(saved.id(), null, deeperPath, null);
        assertThat(a.auth.get()).isEqualTo("Bearer " + KEY_A);
    }

    // ---------------- 默认端口归一化 ----------------

    @Test
    void defaultPortsNormalizeToTheSameOrigin() {
        assertThat(ProviderUrlSecurity.sameOrigin(
                "http://localhost:80/v1", "http://localhost/v1")).isTrue();
        assertThat(ProviderUrlSecurity.sameOrigin(
                "https://example.com:443/v1", "https://example.com/v1")).isTrue();
        assertThat(ProviderUrlSecurity.sameOrigin(
                "http://LOCALHOST/v1", "http://localhost:80/v1")).isTrue();
        assertThat(ProviderUrlSecurity.sameOrigin(
                "http://localhost:8080/v1", "http://localhost/v1")).isFalse();
        assertThat(ProviderUrlSecurity.sameOrigin(
                "http://localhost/v1", "https://localhost/v1")).isFalse();
        assertThat(ProviderUrlSecurity.sameOrigin(
                "http://localhost/v1", "http://127.0.0.1/v1")).isFalse();
    }

    // ---------------- 统一 provider:修改地址的保存路径 ----------------

    @Test
    void changingOriginOnSaveRequiresExplicitKeyDecision() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        // 沿用密钥(patch.apiKey() == null)+ 跨来源 → 必须拒绝
        assertThatThrownBy(() -> providers.update(saved.id(), new ModelProvidersService.Patch(
                null, null, null, b.baseUrl, null, null, null)))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("different origin");
        // 已存配置原样保留
        ModelProviderRecord unchanged = providers.require(saved.id());
        assertThat(unchanged.baseUrl()).isEqualTo(a.baseUrl);
        assertThat(unchanged.apiKey()).isEqualTo(KEY_A);
        // 显式输入新密钥 → 允许,且旧密钥被替换
        ModelProviderRecord updated = providers.update(saved.id(), new ModelProvidersService.Patch(
                null, null, null, b.baseUrl, KEY_B, null, null));
        assertThat(updated.baseUrl()).isEqualTo(b.baseUrl);
        assertThat(updated.apiKey()).isEqualTo(KEY_B);
    }

    @Test
    void sameOriginSaveStillKeepsKeyWithoutReentry() {
        ModelProviderRecord saved = createProviderAt(a.baseUrl, KEY_A);
        String variantUrl = a.baseUrl; // 同一来源的相同地址
        ModelProviderRecord updated = providers.update(saved.id(), new ModelProvidersService.Patch(
                null, null, null, variantUrl, null, null, null));
        assertThat(updated.apiKey()).isEqualTo(KEY_A);
    }

    // ---------------- 旧单例 Custom 接口 ----------------

    @Test
    void legacyCustomDiscoverBlocksCrossOriginKeyReuse() {
        custom.save("CHAT_COMPLETIONS", a.baseUrl, KEY_A, "m1", "DISCOVERED");
        assertThatThrownBy(() -> custom.discover("CHAT_COMPLETIONS", b.baseUrl, null))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("different origin");
        assertThat(b.requests.get()).isZero();
        assertThat(b.auth.get()).isNull();
    }

    @Test
    void legacyCustomDiscoverSameOriginReusesAndExplicitKeyOverrides() {
        custom.save("CHAT_COMPLETIONS", a.baseUrl, KEY_A, "m1", "DISCOVERED");
        custom.discover("CHAT_COMPLETIONS", a.baseUrl, null);
        assertThat(a.auth.get()).isEqualTo("Bearer " + KEY_A);
        custom.discover("CHAT_COMPLETIONS", b.baseUrl, KEY_B);
        assertThat(b.auth.get()).isEqualTo("Bearer " + KEY_B);
        custom.discover("CHAT_COMPLETIONS", b.baseUrl, "");
        assertThat(b.auth.get()).isNull();
    }

    @Test
    void legacyCustomSaveWithChangedOriginRequiresExplicitKeyDecision() {
        custom.save("CHAT_COMPLETIONS", a.baseUrl, KEY_A, "m1", "DISCOVERED");
        // 跨来源 + 沿用密钥 → 拒绝
        assertThatThrownBy(() -> custom.save("CHAT_COMPLETIONS", b.baseUrl, null, "m1", null))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("different origin");
        // 跨来源 + 显式新密钥 → 允许
        custom.save("CHAT_COMPLETIONS", b.baseUrl, KEY_B, "m1", null);
        var status = custom.status();
        assertThat(status.baseUrl()).isEqualTo(b.baseUrl);
        assertThat(status.hasKey()).isTrue();
    }

    @Test
    void legacyCustomSaveWithClearedKeyOnNewOriginIsAllowed() {
        custom.save("CHAT_COMPLETIONS", a.baseUrl, KEY_A, "m1", "DISCOVERED");
        // 跨来源 + 显式清空(空串 = 无鉴权)→ 允许
        custom.save("CHAT_COMPLETIONS", b.baseUrl, "", "m1", null);
        assertThat(custom.status().hasKey()).isFalse();
    }
}
