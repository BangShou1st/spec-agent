package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.api.GaNativeModelBroker;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.model.contract.CustomNativeToolQualification;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaNativeInferenceGateway;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.provider.RoutingGaNativeInferenceGateway;
import com.specagent.support.FakeModelProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic fake-provider closed loop: sidecar persists and re-injects private state. */
@SpringBootTest
@ActiveProfiles("test")
class GaModelContinuationEndToEndTest {
    @Autowired GaExecutionStore executions;
    @Autowired RoutingGaNativeInferenceGateway routingGateway;
    @Autowired com.specagent.assistant.runtime.GaModelContinuationStore continuations;
    @Autowired ConversationDeleteService deletion;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    FakeModelProvider provider;
    GaNativeModelBroker broker;
    UUID thread, run, lease, bindingId;
    GaExecutionStore.Scope scope;
    GaExecutionStore.Binding binding;

    @BeforeEach void fixture() throws Exception {
        provider = new FakeModelProvider();
        String baseUrl = provider.baseUrl();
        UUID providerId = UUID.randomUUID();
        CustomRuntimeSettingsPort settings = () -> new RuntimeCustomSettings(
                "CHAT_COMPLETIONS", baseUrl, "fixture-secret", "fixture-model", 1, providerId, true);
        String revision = settings.requireRuntimeSettings().bindingRevision();
        var textChat = new com.specagent.model.provider.ChatCompletionsGaTextTransport(
                settings, mapper,
                new CustomNativeToolQualification() {
                    public boolean hasCustomTargets() { return true; }
                    public boolean customQualified(String base, String model) { return true; }
                });
        var gateway = new GaNativeInferenceGateway() {
            @Override public GaModelContract.Response complete(com.specagent.model.contract.GaModelTarget target,
                    UUID conversationId, GaModelContract.Request request, java.time.Duration remaining,
                    java.util.function.BooleanSupplier active) {
                return textChat.complete(target, request, remaining, active);
            }
            @Override public GaModelContract.Response stream(com.specagent.model.contract.GaModelTarget target,
                    UUID conversationId, GaModelContract.Request request, java.time.Duration remaining,
                    java.util.function.BooleanSupplier active,
                    com.specagent.model.contract.FragmentListener listener) {
                return textChat.stream(target, request, remaining, active, listener);
            }
        };
        var resolver = org.mockito.Mockito.mock(GaExecutionModelTargetResolver.class);
        org.mockito.Mockito.when(resolver.requireFrozen("CUSTOM", "fixture-model", revision))
                .thenReturn(new com.specagent.model.contract.GaModelTarget("CUSTOM", "fixture-model", revision, "fixture-secret"));
        broker = new GaNativeModelBroker(executions, resolver, gateway, continuations);
        thread = conversations.createThread().id();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        lease = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        scope = new GaExecutionStore.Scope(run, 1, lease);
        binding = new GaExecutionStore.Binding(thread, bindingId, "CUSTOM", "fixture-model", revision,
                Instant.now().plusSeconds(120), List.of(new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"))));
        executions.initialize(scope, binding);
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
    }

    @AfterEach void cleanup() {
        try { provider.close(); } catch (Exception ignored) {}
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
    }

    @Test void toolCallResultFinalRoundTripKeepsPrivateStateOnlyInJava() throws Exception {
        var user = new GaModelContract.Message("user", "probe", List.of(), null);
        var firstRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId, List.of(user), binding.allowedTools(),
                "required", 256, false);
        var first = broker.complete(firstRequest);
        String callId = first.toolCalls().getFirst().id();
        assertNotNull(first.continuationRef());
        assertTrue(first.toolCalls().getFirst().extraContent() == null);
        assertFalse(mapper.writeValueAsString(first).contains("fixture-signature"));

        var assistant = new GaModelContract.Message("assistant", "", List.of(
                new GaModelContract.ToolCall(callId, "probe_echo", Map.of("text", "probe ok"))),
                null, first.continuationRef(), null);
        var tool = new GaModelContract.Message("tool", "{\"status\":\"SUCCEEDED\"}", List.of(), callId);
        var secondRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId, List.of(user, assistant, tool),
                binding.allowedTools(), "auto", 256, false);
        var second = broker.complete(secondRequest);
        assertEquals("fixture answer", second.content());

        // Provider received the re-injected private state on the follow-up call only.
        var history = provider.requests.get(1).path("messages");
        assertEquals("fixture-signature", history.get(1).path("tool_calls").get(0)
                .path("extra_content").path("google").path("thought_signature").asText());
        // Ledger and sidecar never expose plaintext private material on the wire surface.
        var ledger = jdbc.queryForObject("SELECT result FROM ga_execution_calls WHERE run_id=? AND kind='MODEL' ORDER BY call_sequence LIMIT 1",
                String.class, run);
        assertFalse(ledger.contains("fixture-signature"));
        var sidecar = jdbc.queryForList("SELECT payload FROM ga_model_continuations", String.class);
        assertFalse(String.join("", sidecar).contains("fixture-signature"));
    }
}
