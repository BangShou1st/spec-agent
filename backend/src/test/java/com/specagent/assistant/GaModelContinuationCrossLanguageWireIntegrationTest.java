package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.assistant.api.GaNativeModelBroker;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.assistant.runtime.GaModelContinuationStore;
import com.specagent.model.contract.CustomNativeToolQualification;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaNativeInferenceGateway;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.support.FakeModelProvider;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Replays real Python-serialized JSON through the production Java wire parser,
 * broker, ledger and encrypted sidecar, then verifies the re-injected provider
 * signature on the second fake-provider call.
 */
@SpringBootTest
@ActiveProfiles("test")
class GaModelContinuationCrossLanguageWireIntegrationTest {
    @Autowired GaExecutionStore executions;
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
    Path fixtures = Path.of("../contracts/global-assistant/fixtures/ms-c1-r1");

    @BeforeEach void fixture() throws Exception {
        provider = new FakeModelProvider();
        UUID providerId = UUID.randomUUID();
        CustomRuntimeSettingsPort settings = () -> new RuntimeCustomSettings(
                "CHAT_COMPLETIONS", provider.baseUrl(), "fixture-secret", "fixture-model", 1, providerId, true);
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
        JsonNode identity = mapper.readTree(Files.readString(fixtures.resolve("identity.json")));
        thread = conversations.createThread().id();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        lease = UUID.fromString(identity.path("leaseId").asText());
        bindingId = UUID.fromString(identity.path("bindingId").asText());
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

    private String rebindIds(String json) throws Exception {
        JsonNode tree = mapper.readTree(json);
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
        root.put("runId", run.toString());
        root.put("modelBindingId", bindingId.toString());
        // The Python fixture uses a stable first call id; rebind only the second call.
        return mapper.writeValueAsString(root);
    }

    @Test void pythonJsonWireRoundTripPreservesRefAndReinjectsSignature() throws Exception {
        String firstJson = rebindIds(Files.readString(fixtures.resolve("python-first-request.json")));
        var firstWire = GaModelContract.readRequest(firstJson);
        assertEquals("ga-model-inference.v2", firstWire.protocolVersion());
        assertFalse(firstJson.contains("extraContent"), "Python v2 must not serialize provider fields");

        var first = broker.complete(firstWire);
        assertEquals("fixture-call", first.toolCalls().getFirst().id());
        assertNotNull(first.continuationRef());
        assertTrue(firstJson.contains("toolCalls\":[]"), "Pydantic strict serialization shape is replayed");

        String secondJson = Files.readString(fixtures.resolve("python-second-request.json"))
                .replace("gacr-00000000-0000-4000-8000-000000000042", first.continuationRef());
        secondJson = rebindIds(secondJson);
        var secondWire = GaModelContract.readRequest(secondJson);
        assertEquals(first.continuationRef(), secondWire.messages().get(1).continuationRef());

        var second = broker.complete(secondWire);
        assertEquals("fixture answer", second.content());
        var history = provider.requests.get(1).path("messages");
        assertEquals("fixture-signature", history.get(1).path("tool_calls").get(0)
                .path("extra_content").path("google").path("thought_signature").asText());
        var ledger = jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=? AND kind='MODEL' ORDER BY call_sequence", String.class, run);
        assertFalse(String.join("", ledger).contains("fixture-signature"));
        var sidecar = jdbc.queryForList("SELECT payload FROM ga_model_continuations", String.class);
        assertFalse(String.join("", sidecar).contains("fixture-signature"));
    }
}

