package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.assistant.runtime.GaOwnershipLostCancelPropagator;
import com.specagent.common.BrainConnectionSettings;
import com.specagent.runtime.execution.ExecutionToken;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GaOwnershipLostCancelPropagatorTest {
    private ExecutionToken token() {
        return new ExecutionToken(UUID.randomUUID(), 3, UUID.randomUUID(), UUID.randomUUID(),
                RuntimeTaskFamily.GLOBAL_ASSISTANT, UUID.randomUUID());
    }

    @Test void sendsBindingLeaseAndNeverBlocksHeartbeatOnSlowPython() throws Exception {
        var token = token();
        UUID bindingLease = UUID.randomUUID();
        var store = mock(GaExecutionStore.class);
        when(store.cancellationScope(token.runId(), token.epoch())).thenReturn(
                Optional.of(new GaExecutionStore.Scope(token.runId(), token.epoch(), bindingLease)));
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/global-assistant/executions/" + token.runId() + "/cancel", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            received.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        var settings = new GaBrainSettings("http://127.0.0.1:" + server.getAddress().getPort(), new BrainConnectionSettings() {
            public String getBaseUrl() { return "unused"; }
            public String getInternalSecret() { return "test-only-cancel-secret"; }
        });
        var listener = new GaOwnershipLostCancelPropagator(settings, store);
        try {
            assertThatNoException().isThrownBy(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofMillis(500), () -> listener.onOwnershipLost(token)));
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            var json = new ObjectMapper().readTree(body.get());
            assertThat(json.path("executionEpoch").asLong()).isEqualTo(token.epoch());
            assertThat(json.path("leaseId").asText()).isEqualTo(bindingLease.toString()).isNotEqualTo(token.leaseId().toString());
            // An additional notification still returns while Python is blocked on the first request.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMillis(500), () -> listener.onOwnershipLost(token));
        } finally { release.countDown(); listener.stop(); server.stop(0); }
    }

    @Test void staleNotificationCannotSelectSuccessorAndDoesNotCallPython() throws Exception {
        var token = token();
        var store = mock(GaExecutionStore.class);
        when(store.cancellationScope(token.runId(), token.epoch())).thenReturn(Optional.empty());
        var settings = new GaBrainSettings("http://127.0.0.1:1", new BrainConnectionSettings() {
            public String getBaseUrl() { return "unused"; }
            public String getInternalSecret() { return "test-only-cancel-secret"; }
        });
        var listener = new GaOwnershipLostCancelPropagator(settings, store);
        try {
            listener.onOwnershipLost(token);
            verify(store, timeout(2000)).cancellationScope(token.runId(), token.epoch());
            verifyNoMoreInteractions(store);
        } finally { listener.stop(); }
    }
}
