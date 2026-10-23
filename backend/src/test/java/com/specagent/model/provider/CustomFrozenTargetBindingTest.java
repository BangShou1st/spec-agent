package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.RoutingModelInferenceGateway;
import com.specagent.modelsettings.ActiveModelTargetService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CustomFrozenTargetBindingTest {

    private static final String MODEL = "same-model-id";

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger serverARequests = new AtomicInteger();
    private final AtomicInteger serverBRequests = new AtomicInteger();
    private HttpServer serverA;
    private HttpServer serverB;

    @AfterEach
    void stopServers() {
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
    }

    private HttpServer server(boolean isA) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            if (isA) serverARequests.incrementAndGet(); else serverBRequests.incrementAndGet();
            paths.add(exchange.getRequestURI().toString());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "<missing>" : auth);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = isA
                    ? "{\"choices\":[{\"message\":{\"content\":\"a\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"status\":\"completed\",\"output_text\":\"b\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private RuntimeCustomSettings config(String format, String baseUrl, String key, long revision) {
        return new RuntimeCustomSettings(format, baseUrl, key, MODEL, revision);
    }

    private RoutingModelInferenceGateway gateway(CustomRuntimeSettingsPort port,
                                                   AtomicReference<RuntimeCustomSettings> current,
                                                   boolean switchAfterFirstSnapshot,
                                                   AtomicInteger settingsReads) {
        ModelProviderSettingsService providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        when(providers.selection()).thenAnswer(i -> new ModelProviderSettingsService.Selection(providers.activeProvider(),null));
        CustomRuntimeSettingsPort countedPort = () -> {
            settingsReads.incrementAndGet();
            return port.requireRuntimeSettings();
        };
        ActiveModelTargetService resolver = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), mock(KiloRuntimeSettingsPort.class),
                countedPort);
        AtomicInteger snapshots = new AtomicInteger();
        ActiveModelTargetPort targets = () -> {
            Optional<ActiveModelTargetPort.Target> target = resolver.activeTarget();
            if (switchAfterFirstSnapshot && snapshots.getAndIncrement() == 0) {
                current.set(config("RESPONSES", base(serverB), "key-B", 2));
            }
            return target;
        };
        ProtocolAdapterRegistry registry = new ProtocolAdapterRegistry(List.of(
                new ChatCompletionsProtocolAdapter(), new ResponsesProtocolAdapter(),
                new AnthropicMessagesProtocolAdapter()));
        CustomInferenceGateway custom = new CustomInferenceGateway(countedPort, registry, new ObjectMapper());
        return new RoutingModelInferenceGateway(mock(ActiveProviderPort.class), targets,
                mock(OpenCodeModelInferenceGateway.class), mock(KiloInferenceGateway.class), custom, new InMemoryRunModelBindingPort());
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void sameModelChangesToEndpointFormatKeyOrRevisionChangeTheBindingFingerprint() throws Exception {
        serverA = server(true);
        serverB = server(false);
        RuntimeCustomSettings baseline = config("CHAT_COMPLETIONS", base(serverA), "key-A", 1);
        String fingerprint = new ActiveModelTargetPort.Target("CUSTOM", MODEL, baseline).bindingFingerprint();

        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                config("CHAT_COMPLETIONS", base(serverB), "key-A", 1)).bindingFingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                config("RESPONSES", base(serverA), "key-A", 1)).bindingFingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                config("CHAT_COMPLETIONS", base(serverA), "key-B", 1)).bindingFingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                config("CHAT_COMPLETIONS", base(serverA), "key-A", 2)).bindingFingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL, baseline).toString())
                .doesNotContain("key-A");
    }

    @Test
    void otherProvidersAlsoBindCredentialsAndTheirRuntimeConfiguration() {
        String openCode = new ActiveModelTargetPort.Target("OPENCODE_ZEN", MODEL,
                new RuntimeOpenCodeSettings("key-A", MODEL, "database:opencode_settings"))
                .bindingFingerprint();
        assertThat(new ActiveModelTargetPort.Target("OPENCODE_ZEN", MODEL,
                new RuntimeOpenCodeSettings("key-B", MODEL, "database:opencode_settings"))
                .bindingFingerprint()).isNotEqualTo(openCode);
        assertThat(new ActiveModelTargetPort.Target("OPENCODE_ZEN", MODEL,
                new RuntimeOpenCodeSettings("key-A", MODEL, "public-fallback"))
                .bindingFingerprint()).isNotEqualTo(openCode);

        String openRouter = new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "http://localhost:9/v1", "key-A", MODEL, 1))
                .bindingFingerprint();
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "http://localhost:9/v1", "key-B", MODEL, 1))
                .bindingFingerprint()).isNotEqualTo(openRouter);
        assertThat(new ActiveModelTargetPort.Target("CUSTOM", MODEL,
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "http://localhost:9/v1", "key-A", MODEL, 2))
                .bindingFingerprint()).isNotEqualTo(openRouter);

        String kilo = new ActiveModelTargetPort.Target("KILO", MODEL,
                new RuntimeKiloSettings("key-A", MODEL, "database:kilo_settings", "rev-1",
                        "https://gateway-a.example/api/v1"))
                .bindingFingerprint();
        assertThat(new ActiveModelTargetPort.Target("KILO", MODEL,
                new RuntimeKiloSettings("key-B", MODEL, "database:kilo_settings", "rev-1",
                        "https://gateway-a.example/api/v1"))
                .bindingFingerprint()).isNotEqualTo(kilo);
        assertThat(new ActiveModelTargetPort.Target("KILO", MODEL,
                new RuntimeKiloSettings("key-A", MODEL, "database:kilo_settings", "rev-2",
                        "https://gateway-a.example/api/v1"))
                .bindingFingerprint()).isNotEqualTo(kilo);
        assertThat(new ActiveModelTargetPort.Target("KILO", MODEL,
                new RuntimeKiloSettings("key-A", MODEL, "database:kilo_settings", "rev-1",
                        "https://gateway-b.example/api/v1"))
                .bindingFingerprint()).isNotEqualTo(kilo);
    }

    @Test
    void oneRunUsesItsResolvedEndpointAndCredentialAndNewRunUsesUpdatedCustomSettings() throws Exception {
        serverA = server(true);
        serverB = server(false);
        AtomicReference<RuntimeCustomSettings> current = new AtomicReference<>(
                config("CHAT_COMPLETIONS", base(serverA), "key-A", 1));
        CustomRuntimeSettingsPort source = current::get;
        AtomicInteger settingsReads = new AtomicInteger();
        RoutingModelInferenceGateway gateway = gateway(source, current, true, settingsReads);
        UUID firstRun = UUID.randomUUID();

        gateway.complete(request(firstRun));

        assertThat(serverARequests.get()).isEqualTo(1);
        assertThat(serverBRequests.get()).isZero();
        assertThat(paths).containsExactly("/v1/chat/completions");
        assertThat(authorizations).containsExactly("Bearer key-A");
        assertThat(new ObjectMapper().readTree(bodies.getFirst()).path("model").asText()).isEqualTo(MODEL);
        assertThat(settingsReads.get()).isEqualTo(1);

        assertThatThrownBy(() -> gateway.complete(request(firstRun)))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH")
                .hasMessageNotContaining("key-A")
                .hasMessageNotContaining("key-B");
        assertThat(serverBRequests.get()).isZero();

        gateway.complete(request(UUID.randomUUID()));

        assertThat(serverBRequests.get()).isEqualTo(1);
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/responses");
        assertThat(authorizations).containsExactly("Bearer key-A", "Bearer key-B");
        assertThat(new ObjectMapper().readTree(bodies.get(1)).path("model").asText()).isEqualTo(MODEL);
        assertThat(settingsReads.get()).isEqualTo(3);
    }

    private static ModelInferenceRequest request(UUID runId) {
        return new ModelInferenceRequest(runId, "DECISION",
                List.of(new ModelInferenceMessage("user", "hello")), 128);
    }
}
