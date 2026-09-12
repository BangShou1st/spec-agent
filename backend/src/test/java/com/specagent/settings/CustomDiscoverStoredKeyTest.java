package com.specagent.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.specagent.model.provider.AnthropicMessagesProtocolAdapter;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ResponsesProtocolAdapter;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.settings.custom.CustomProviderSettings;
import com.specagent.settings.custom.CustomProviderSettingsRepository;
import com.specagent.settings.custom.CustomProviderSettingsService;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** discover(null) reuses the stored key; only explicit empty means no auth. */
class CustomDiscoverStoredKeyTest {

    static class MemRepo implements CustomProviderSettingsRepository {
        CustomProviderSettings stored;
        public Optional<CustomProviderSettings> find() {
            return Optional.ofNullable(stored);
        }
        public void upsert(CustomProviderSettings s) {
            stored = s;
        }
        public void markValidated(long rev) {
        }
    }

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final MemRepo repo = new MemRepo();
    private CustomProviderSettingsService svc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = MAPPER.writeValueAsBytes(Map.of("data",
                    List.of(Map.of("id", "m1"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ObjectMapper mapper = new ObjectMapper();
        ProtocolAdapterRegistry reg = new ProtocolAdapterRegistry(List.of(
                new ChatCompletionsProtocolAdapter(),
                new ResponsesProtocolAdapter(),
                new AnthropicMessagesProtocolAdapter()));
        svc = new CustomProviderSettingsService(repo, mapper, reg,
                new CompatibilityProbeService(mapper, reg,
                        new GlobalAssistantDecisionParser(mapper),
                        new GlobalAssistantDecisionValidator()));
    }

    @AfterEach void stop() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }

    private void storeKey(String key) {
        Instant now = Instant.now();
        repo.stored = new CustomProviderSettings("CHAT_COMPLETIONS", baseUrl, key,
                key == null ? null : "KEY1", "m1", "DISCOVERED", 1, null, now, now, null);
    }

    @Test void storedKeyUsedWhenNull() {
        storeKey("stored-secret");
        var r = svc.discover("CHAT_COMPLETIONS", baseUrl, null);
        assertThat(r.models()).contains("m1");
        assertThat(auth.get()).isEqualTo("Bearer stored-secret");
    }

    @Test void replacementKeyWins() {
        storeKey("stored-secret");
        svc.discover("CHAT_COMPLETIONS", baseUrl, "replacement");
        assertThat(auth.get()).isEqualTo("Bearer replacement");
    }

    @Test void noStoredKeyMeansUnauthenticated() {
        var r = svc.discover("CHAT_COMPLETIONS", baseUrl, null);
        assertThat(r.models()).contains("m1");
        assertThat(auth.get()).isNull();
    }

    @Test void explicitEmptyMeansUnauthenticated() {
        storeKey("stored-secret");
        svc.discover("CHAT_COMPLETIONS", baseUrl, "");
        assertThat(auth.get()).isNull();
    }
}
