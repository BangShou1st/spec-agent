package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.config.GaNativeModelQualification;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelProvider;
import com.specagent.modelsettings.CustomProviderSettingsService;
import com.specagent.modelsettings.KiloSettings;
import com.specagent.modelsettings.KiloSettingsRepository;
import com.specagent.modelsettings.KiloSettingsService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.modelsettings.OpenCodeSettingsService;
import com.specagent.modelsettings.OpenRouterSettingsService;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 文件名:KiloCredentialBoundaryTest.java
 *
 * 测试目标:免费 Kilo 模型的凭据发送边界——用一个 loopback HTTP 桩检查<b>真实请求头</b>。
 * 免费模型即使之前保存过付费密钥也绝不发送 {@code Authorization};付费模型带合法密钥时
 * 正确带上;付费模型缺密钥时在发起请求之前失败关闭。GA 与普通 Project Agent 两条链路
 * 必须遵守同一策略,且各自一次调用只产生一次模型请求(无额外探测/重试)。
 */
class KiloCredentialBoundaryTest {

    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";
    private static final String PAID_MODEL = "cohere/north-mini-code";

    /** 每个 /chat/completions 请求捕获到的 Authorization 头(缺失时为 null)。 */
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ── loopback 桩 ────────────────────────────────────────────────────────────

    private KiloGatewayProperties sseServer() throws IOException {
        List<String> frames = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "data: [DONE]");
        return server("text/event-stream", String.join("\n\n", frames) + "\n\n");
    }

    private KiloGatewayProperties jsonServer() throws IOException {
        return server("application/json",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
    }

    private KiloGatewayProperties server(String contentType, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "<missing>" : authorization);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        KiloGatewayProperties properties = new KiloGatewayProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setRequestTimeout(Duration.ofSeconds(10));
        properties.setFreeOnly(false);
        return properties;
    }

    // ── 真实设置服务与目标解析 ──────────────────────────────────────────────────

    private static KiloSettings stored(String key, String model) {
        Instant now = Instant.now();
        return new KiloSettings(key, key == null ? null : "1234", model, now, now);
    }

    private static KiloSettingsRepository repository(KiloSettings settings) {
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        when(repository.find()).thenReturn(Optional.ofNullable(settings));
        return repository;
    }

    private KiloSettingsService kiloService(KiloGatewayProperties properties, KiloSettingsRepository repository) {
        return new KiloSettingsService(repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
    }

    private static GaExecutionModelTargetResolver resolver(KiloSettingsService kiloSettings) {
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        GaBrainSettings host = mock(GaBrainSettings.class);
        when(host.getInternalSecret()).thenReturn("secret");
        GaNativeModelQualification qualification = mock(GaNativeModelQualification.class);
        when(qualification.kiloQualified(anyString())).thenReturn(false);
        return new GaExecutionModelTargetResolver(providers,
                mock(OpenCodeSettingsRepository.class), mock(OpenCodeSettingsService.class),
                mock(OpenCodeModelCatalog.class), kiloSettings, mock(OpenRouterSettingsService.class),
                mock(CustomProviderSettingsService.class), qualification, host);
    }

    private static GaExecutionModelTargetResolver.Ready ready(
            GaExecutionModelTargetResolver.Resolution resolution) {
        assertThat(resolution).isInstanceOf(GaExecutionModelTargetResolver.Ready.class);
        return (GaExecutionModelTargetResolver.Ready) resolution;
    }

    private static GaModelContract.Request gaRequest() {
        List<GaModelContract.Message> messages = List.of(new GaModelContract.Message("user", "hi", null, null));
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(), messages, List.of(), "none", 128, false);
    }

    private static ModelInferenceRequest agentRequest() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "hi")), 128);
    }

    // ── GA:冻结目标 → 原生传输 → 真实请求头 ─────────────────────────────────────

    @Test
    void gaFreeRunWithoutAnyStoredKeyIsAnonymous() throws IOException {
        KiloGatewayProperties properties = sseServer();
        KiloSettingsService settings = kiloService(properties, repository(stored(null, FREE_MODEL)));

        GaModelTarget target = ready(resolver(settings).resolveActive()).target();

        new KiloGaNativeTransport(properties).complete(target, gaRequest(), Duration.ofSeconds(30), () -> true);

        assertThat(authorizations).hasSize(1);
        assertThat(authorizations.get(0)).isEqualTo("<missing>");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void gaFreeRunIsAnonymousEvenWithAStoredPaidKey() throws IOException {
        KiloGatewayProperties properties = sseServer();
        KiloSettingsService settings = kiloService(properties, repository(stored("paid-key", FREE_MODEL)));

        GaModelTarget target = ready(resolver(settings).resolveActive()).target();
        assertThat(target.hasCredential()).isFalse();

        new KiloGaNativeTransport(properties).complete(target, gaRequest(), Duration.ofSeconds(30), () -> true);

        assertThat(authorizations).hasSize(1);
        assertThat(authorizations.get(0)).isEqualTo("<missing>");
        // 不因修复而发生额外模型请求:一次调用一次请求。
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void gaPaidRunSendsTheResolvedCredential() throws IOException {
        KiloGatewayProperties properties = sseServer();
        KiloSettingsService settings = kiloService(properties, repository(stored("paid-key", PAID_MODEL)));

        GaModelTarget target = ready(resolver(settings).resolveActive()).target();
        assertThat(target.credential()).isEqualTo("paid-key");

        new KiloGaNativeTransport(properties).complete(target, gaRequest(), Duration.ofSeconds(30), () -> true);

        assertThat(authorizations).containsExactly("Bearer paid-key");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void gaPaidRunWithoutAKeyIsRejectedBeforeAnyRequest() throws IOException {
        KiloGatewayProperties properties = sseServer();
        KiloSettingsService settings = kiloService(properties, repository(stored(null, PAID_MODEL)));

        var resolution = resolver(settings).resolveActive();

        assertThat(resolution).isInstanceOf(GaExecutionModelTargetResolver.Unavailable.class);
        assertThat(requests.get()).isZero();
        assertThat(authorizations).isEmpty();
    }

    // ── Project Agent:KiloRuntimeSettingsPort → 推理网关 → 真实请求头 ────────────

    private KiloInferenceGateway projectAgent(KiloGatewayProperties properties, KiloSettingsService settings) {
        return new KiloInferenceGateway(settings, properties, new ObjectMapper());
    }

    @Test
    void projectAgentFreeRunIsAnonymousEvenWithAStoredPaidKey() throws IOException {
        KiloGatewayProperties properties = jsonServer();
        KiloSettingsService settings = kiloService(properties, repository(stored("paid-key", FREE_MODEL)));

        ModelInferenceResponse response = projectAgent(properties, settings).complete(agentRequest());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(authorizations).hasSize(1);
        assertThat(authorizations.get(0)).isEqualTo("<missing>");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void projectAgentPaidRunSendsTheStoredCredential() throws IOException {
        KiloGatewayProperties properties = jsonServer();
        KiloSettingsService settings = kiloService(properties, repository(stored("paid-key", PAID_MODEL)));

        ModelInferenceResponse response = projectAgent(properties, settings).complete(agentRequest());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(authorizations).containsExactly("Bearer paid-key");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void projectAgentPaidRunWithoutAKeyIsRejectedBeforeAnyRequest() throws IOException {
        KiloGatewayProperties properties = jsonServer();
        KiloSettingsService settings = kiloService(properties, repository(stored(null, PAID_MODEL)));

        assertThat(catchThrowable(() -> projectAgent(properties, settings).complete(agentRequest())))
                .isInstanceOf(ModelProviderException.class);
        assertThat(requests.get()).isZero();
    }

    // ── 切换模型:新调用使用正确凭据(旧调用不受影响) ───────────────────────────

    @Test
    void switchingTheSelectedModelChangesTheCredentialOnTheNextCall() throws IOException {
        KiloGatewayProperties properties = sseServer();
        KiloSettingsRepository repository = repository(stored("paid-key", PAID_MODEL));
        KiloSettingsService settings = kiloService(properties, repository);

        // 付费模型:携带已存密钥。
        GaModelTarget paid = ready(resolver(settings).resolveActive()).target();
        assertThat(paid.credential()).isEqualTo("paid-key");

        // 用户切换到免费模型后,新的解析必须匿名——旧 Run 的冻结目标不受影响。
        when(repository.find()).thenReturn(Optional.of(stored("paid-key", FREE_MODEL)));
        GaModelTarget free = ready(resolver(settings).resolveActive()).target();
        assertThat(free.hasCredential()).isFalse();
        assertThat(paid.credential()).isEqualTo("paid-key");
    }

    @Test
    void changedModelUsesTheCorrectCredentialForBothPaths() throws IOException {
        KiloGatewayProperties properties = jsonServer();
        KiloSettingsRepository repository = repository(stored(null, FREE_MODEL));
        KiloSettingsService settings = kiloService(properties, repository);

        // 免费:Project Agent 匿名。
        projectAgent(properties, settings).complete(agentRequest());
        assertThat(authorizations.get(0)).isEqualTo("<missing>");

        // 切到付费模型并带上密钥:Project Agent 携带该密钥。
        when(repository.find()).thenReturn(Optional.of(stored("paid-key", PAID_MODEL)));
        projectAgent(properties, settings).complete(agentRequest());
        assertThat(authorizations).containsExactly("<missing>", "Bearer paid-key");
        assertThat(requests.get()).isEqualTo(2);
    }
}
