package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.modelsettings.KiloSettings;
import com.specagent.modelsettings.KiloSettingsRepository;
import com.specagent.modelsettings.KiloSettingsService;
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

    private final List<String> bodies = new CopyOnWriteArrayList<>();
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
}
