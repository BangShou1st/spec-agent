package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.OpenRouterRuntimeSettingsPort;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.OpenRouterInferenceGateway;
import com.specagent.model.provider.RoutingModelInferenceGateway;
import com.specagent.modelsettings.ActiveModelTargetService;
import com.specagent.modelsettings.KiloSettings;
import com.specagent.modelsettings.KiloSettingsRepository;
import com.specagent.modelsettings.KiloSettingsService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.model.contract.ModelProvider;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 文件名:KiloFrozenModelBindingTest.java
 *
 * 测试目标:证明"传给网关的冻结模型"确实决定了<b>真实发出的请求体</b>里的
 * {@code model} 字段——用一个 loopback HTTP 桩读取线上 JSON,而不是只看方法调用。
 *
 * 这是路由网关"冻结检查与网关构造请求之间不再发生模型漂移"的最后一环:设置行里的
 * 所选模型可以任意变化,但只要调用方传入冻结模型,发出去的 model 就只能是冻结模型。
 */
class KiloFrozenModelBindingTest {

    private static final String SETTINGS_MODEL = "settings-model:free";
    private static final String FROZEN_MODEL = "poolside/laguna-s-2.1:free";
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";

    private final List<String> bodies = new CopyOnWriteArrayList<>();
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

    private KiloGatewayProperties stubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "<missing>" : authorization);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
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

    private KiloInferenceGateway gateway(KiloGatewayProperties properties, String settingsModel) {
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        Instant now = Instant.now();
        when(repository.find()).thenReturn(Optional.of(
                new KiloSettings(null, null, settingsModel, now, now)));
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        return new KiloInferenceGateway(settings, properties, new ObjectMapper());
    }

    private static KiloSettings stored(String key, String model) {
        Instant now = Instant.now();
        return new KiloSettings(key, key == null ? null : "1234", model, now, now);
    }

    private RoutingModelInferenceGateway routedGateway(KiloGatewayProperties properties,
                                                        KiloSettingsRepository repository,
                                                        boolean switchFreeToPaidAfterSnapshot) {
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        ActiveModelTargetService resolver = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), settings,
                mock(OpenRouterRuntimeSettingsPort.class), mock(CustomRuntimeSettingsPort.class));
        ActiveModelTargetPort targets = () -> {
            Optional<ActiveModelTargetPort.Target> target = resolver.activeTarget();
            if (switchFreeToPaidAfterSnapshot) {
                when(repository.find()).thenReturn(Optional.of(stored("key-for-paid-B", "paid-model-B")));
            }
            return target;
        };
        return new RoutingModelInferenceGateway(mock(ActiveProviderPort.class), targets,
                mock(OpenCodeModelInferenceGateway.class), mock(OpenRouterInferenceGateway.class),
                new KiloInferenceGateway(settings, properties, new ObjectMapper()),
                mock(CustomInferenceGateway.class), new InMemoryRunModelBindingPort());
    }

    private static ModelInferenceRequest request() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "hi")), 128);
    }

    private String sentModel(String rawBody) throws Exception {
        JsonNode node = new ObjectMapper().readTree(rawBody);
        return node.path("model").asText();
    }

    @Test
    void frozenTargetModelIsWhatIsActuallySent() throws Exception {
        KiloInferenceGateway gateway = gateway(stubServer(), SETTINGS_MODEL);

        ModelInferenceResponse response = gateway.complete(request(), FROZEN_MODEL);

        assertThat(response.content()).isEqualTo("ok");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(sentModel(bodies.get(0))).isEqualTo(FROZEN_MODEL);
    }

    /** 对照:不带冻结目标时,网关按设置行的所选模型发送(历史行为不变)。 */
    @Test
    void withoutAFrozenTargetTheStoredModelIsSent() throws Exception {
        KiloInferenceGateway gateway = gateway(stubServer(), SETTINGS_MODEL);

        gateway.complete(request());

        assertThat(requests.get()).isEqualTo(1);
        assertThat(sentModel(bodies.get(0))).isEqualTo(SETTINGS_MODEL);
    }

    /**
     * 设置行在同一 Run 的两次请求之间被切换时,冻结模型仍然只使用第一个值——
     * 第二次调用即便观察到的设置已经变化,发出的 model 仍是冻结值。
     */
    @Test
    void aMidRunSettingsSwitchDoesNotChangeWhatIsSent() throws Exception {
        KiloGatewayProperties properties = stubServer();
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        Instant now = Instant.now();
        AtomicInteger reads = new AtomicInteger();
        when(repository.find()).thenAnswer(invocation -> Optional.of(new KiloSettings(
                null, null, reads.getAndIncrement() == 0 ? FROZEN_MODEL : "switched-model:free",
                now, now)));
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        KiloInferenceGateway gateway = new KiloInferenceGateway(settings, properties, new ObjectMapper());

        gateway.complete(request(), FROZEN_MODEL);
        gateway.complete(request(), FROZEN_MODEL);

        assertThat(requests.get()).isEqualTo(2);
        assertThat(sentModel(bodies.get(0))).isEqualTo(FROZEN_MODEL);
        assertThat(sentModel(bodies.get(1))).isEqualTo(FROZEN_MODEL);
    }

    /**
     * 精确复现路由拿到冻结目标后、Kilo 网关构造 HTTP 前设置切换的窗口。
     * 免费 A 的请求必须仍匿名,不能借用后来付费 B 的密钥。
     */
    @Test
    void routeSnapshotPreventsFreeFrozenModelFromUsingLaterPaidModelKey() throws Exception {
        KiloGatewayProperties properties = stubServer();
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        when(repository.find()).thenReturn(Optional.of(stored(null, FROZEN_MODEL)));
        RoutingModelInferenceGateway routed = routedGateway(properties, repository, true);
        ModelInferenceRequest request = new ModelInferenceRequest(UUID.randomUUID(), "STATE_UPDATE",
                List.of(new ModelInferenceMessage("user", "hi")), 128);

        routed.complete(request);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(sentModel(bodies.getFirst())).isEqualTo(FROZEN_MODEL);
        assertThat(authorizations).containsExactly("<missing>");
    }

    /** 付费 A 的快照在窗口内遇到免费 B,仍使用 A 的密钥,不会匿名请求 A。 */
    @Test
    void routeSnapshotDoesNotDropPaidFrozenModelsCredential() throws Exception {
        KiloGatewayProperties properties = stubServer();
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        when(repository.find()).thenReturn(Optional.of(stored("paid-key-A", "paid-model-A")));
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        ActiveModelTargetService resolver = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), settings,
                mock(OpenRouterRuntimeSettingsPort.class), mock(CustomRuntimeSettingsPort.class));
        ActiveModelTargetPort targets = () -> {
            Optional<ActiveModelTargetPort.Target> target = resolver.activeTarget();
            when(repository.find()).thenReturn(Optional.of(stored(null, FREE_MODEL)));
            return target;
        };
        RoutingModelInferenceGateway routed = new RoutingModelInferenceGateway(
                mock(ActiveProviderPort.class), targets, mock(OpenCodeModelInferenceGateway.class),
                mock(OpenRouterInferenceGateway.class), new KiloInferenceGateway(settings, properties,
                        new ObjectMapper()), mock(CustomInferenceGateway.class),
                new InMemoryRunModelBindingPort());

        routed.complete(request());

        assertThat(requests.get()).isEqualTo(1);
        assertThat(sentModel(bodies.getFirst())).isEqualTo("paid-model-A");
        assertThat(authorizations).containsExactly("Bearer paid-key-A");
    }

    @Test
    void routeSnapshotAlsoPinsKiloGatewayEndpointUntilHttpConstruction() throws Exception {
        KiloGatewayProperties properties = stubServer();
        String capturedBaseUrl = properties.getBaseUrl();
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        when(repository.find()).thenReturn(Optional.of(stored(null, FROZEN_MODEL)));
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        ActiveModelTargetService resolver = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), settings,
                mock(OpenRouterRuntimeSettingsPort.class), mock(CustomRuntimeSettingsPort.class));
        ActiveModelTargetPort targets = () -> {
            Optional<ActiveModelTargetPort.Target> target = resolver.activeTarget();
            properties.setBaseUrl("http://127.0.0.1:1");
            return target;
        };
        RoutingModelInferenceGateway routed = new RoutingModelInferenceGateway(
                mock(ActiveProviderPort.class), targets, mock(OpenCodeModelInferenceGateway.class),
                mock(OpenRouterInferenceGateway.class), new KiloInferenceGateway(settings, properties,
                        new ObjectMapper()), mock(CustomInferenceGateway.class),
                new InMemoryRunModelBindingPort());

        routed.complete(request());

        assertThat(requests.get()).isEqualTo(1);
        assertThat(properties.getBaseUrl()).isNotEqualTo(capturedBaseUrl);
        assertThat(sentModel(bodies.getFirst())).isEqualTo(FROZEN_MODEL);
    }

    /** 同模型的密钥修订一变,同一 Run 失败关闭;新 Run 则立即读取新设置。 */
    @Test
    void sameRunRejectsCredentialRevisionChangeAndNewRunUsesTheNewCredential() throws Exception {
        KiloGatewayProperties properties = stubServer();
        KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
        AtomicReference<KiloSettings> current = new AtomicReference<>(stored("key-A", "paid-model"));
        when(repository.find()).thenAnswer(invocation -> Optional.of(current.get()));
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.KILO);
        KiloSettingsService settings = new KiloSettingsService(
                repository, mock(KiloGatewayClient.class), properties, new ObjectMapper());
        ActiveModelTargetService resolver = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), settings,
                mock(OpenRouterRuntimeSettingsPort.class), mock(CustomRuntimeSettingsPort.class));
        ActiveModelTargetPort targets = resolver::activeTarget;
        RoutingModelInferenceGateway routed = new RoutingModelInferenceGateway(
                mock(ActiveProviderPort.class), targets, mock(OpenCodeModelInferenceGateway.class),
                mock(OpenRouterInferenceGateway.class), new KiloInferenceGateway(settings, properties,
                        new ObjectMapper()), mock(CustomInferenceGateway.class),
                new InMemoryRunModelBindingPort());
        UUID firstRun = UUID.randomUUID();

        routed.complete(new ModelInferenceRequest(firstRun, "STATE_UPDATE",
                List.of(new ModelInferenceMessage("user", "one")), 128));
        routed.complete(new ModelInferenceRequest(firstRun, "DECISION",
                List.of(new ModelInferenceMessage("user", "two")), 128));
        KiloSettings old = current.get();
        current.set(new KiloSettings("key-B", "1234", "paid-model", old.createdAt(), old.updatedAt().plusSeconds(1)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> routed.complete(
                new ModelInferenceRequest(firstRun, "DECISION",
                        List.of(new ModelInferenceMessage("user", "three")), 128)))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");
        routed.complete(new ModelInferenceRequest(UUID.randomUUID(), "STATE_UPDATE",
                List.of(new ModelInferenceMessage("user", "new run")), 128));

        assertThat(requests.get()).isEqualTo(3);
        assertThat(authorizations).containsExactly("Bearer key-A", "Bearer key-A", "Bearer key-B");
    }
}
