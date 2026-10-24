package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.api.GaCapabilityBroker;
import com.specagent.assistant.api.GaNativeModelBroker;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.assistant.runtime.GaModelContinuationStore;
import com.specagent.assistant.tool.GaCatalogProjection;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.provider.ResponsesGaNativeTransport;
import com.specagent.model.provider.RoutingGaNativeInferenceGateway;
import com.specagent.support.FakeResponsesProvider;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MS-C2 production-path closed loop: Python-v2-shaped requests through the
 * Java broker, Fake Responses provider, model ledger, Java capability runtime
 * and the second function_call_output request.
 */
@SpringBootTest
@ActiveProfiles("test")
class GaResponsesNativeToolEndToEndTest {
    @Autowired GaExecutionStore executions;
    @Autowired GaModelContinuationStore continuations;
    @Autowired GaCatalogProjection catalogs;
    @Autowired GaCapabilityBroker capabilities;
    @Autowired ConversationDeleteService deletion;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    FakeResponsesProvider provider;
    GaNativeModelBroker broker;
    UUID thread, run, lease, bindingId;
    GaExecutionStore.Scope scope;
    GaExecutionStore.Binding binding;
    GaCatalogProjection.Descriptor descriptor;

    @BeforeEach
    void fixture() throws Exception {
        provider = new FakeResponsesProvider();
        provider.toolName = "project_search";
        provider.arguments = "{\"query\":\"spec agent\"}";
        UUID providerId = UUID.randomUUID();
        RuntimeCustomSettings settings = new RuntimeCustomSettings("RESPONSES",
                provider.baseUrl(), "fixture-secret", "responses-model", 1, providerId, true);
        CustomRuntimeSettingsPort port = new CustomRuntimeSettingsPort() {
            @Override
            public RuntimeCustomSettings requireRuntimeSettings() {
                return settings;
            }

            @Override
            public RuntimeCustomSettings requireRuntimeSettings(String frozenRevision) {
                if (!settings.bindingRevision().equals(frozenRevision)) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                return settings;
            }
        };
        var responses = new ResponsesGaNativeTransport(port, mapper,
                java.net.http.HttpClient.newHttpClient());
        var routing = new RoutingGaNativeInferenceGateway(
                org.mockito.Mockito.mock(com.specagent.model.provider.OpenCodeZenTransport.class),
                org.mockito.Mockito.mock(com.specagent.model.provider.KiloGaNativeTransport.class),
                org.mockito.Mockito.mock(com.specagent.model.provider.ChatCompletionsGaTextTransport.class),
                responses, port);
        var resolver = org.mockito.Mockito.mock(GaExecutionModelTargetResolver.class);
        org.mockito.Mockito.when(resolver.requireFrozen("CUSTOM", "responses-model", settings.bindingRevision()))
                .thenReturn(new GaModelTarget("CUSTOM", "responses-model",
                        settings.bindingRevision(), "fixture-secret"));
        broker = new GaNativeModelBroker(executions, resolver, routing, continuations);

        descriptor = catalogs.current().descriptors().stream()
                .filter(item -> "project.search".equals(item.capabilityId()))
                .findFirst().orElseThrow();
        var tool = catalogs.current().tools().stream()
                .filter(item -> item.name().equals(descriptor.name()))
                .findFirst().orElseThrow();
        thread = conversations.createThread().id();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        lease = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        scope = new GaExecutionStore.Scope(run, 1, lease);
        binding = new GaExecutionStore.Binding(thread, bindingId, "CUSTOM", "responses-model",
                settings.bindingRevision(), Instant.now().plusSeconds(120), List.of(tool));
        executions.initialize(scope, binding, List.of(descriptor));
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
    }

    @AfterEach
    void cleanup() {
        try {
            provider.close();
        } catch (Exception ignored) {
        }
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
    }

    @Test
    void responsesFunctionCallRoundTripExecutesOnlyThroughJavaCapabilityRuntime() throws Exception {
        var user = new GaModelContract.Message("user", "search projects", List.of(), null);
        var firstRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId, List.of(user), binding.allowedTools(),
                "required", 256, false);
        var first = broker.complete(firstRequest);

        assertEquals("tool_calls", first.finishReason());
        assertEquals(1, first.toolCalls().size());
        var call = first.toolCalls().getFirst();
        assertEquals(descriptor.name(), call.name());
        assertEquals(Map.of("query", "spec agent"), call.arguments());
        assertTrue(mapper.writeValueAsString(first).contains("\"toolCalls\":[{"));
        assertFalse(mapper.writeValueAsString(first).contains("responses"));

        var invocation = mapper.writeValueAsString(Map.of(
                "protocolVersion", "ga-capability-invocation.v1",
                "runId", run.toString(),
                "executionEpoch", 1,
                "leaseId", lease.toString(),
                "toolCallId", call.id(),
                "capabilityId", descriptor.capabilityId(),
                "descriptorVersion", descriptor.version(),
                "catalogHash", executions.catalog(scope).hash(),
                "arguments", call.arguments(),
                "argumentsHash", com.specagent.common.Hashes.sha256Hex(
                        catalogs.canonical(call.arguments()))));
        var capabilityResult = capabilities.invoke(invocation);
        assertEquals(200, capabilityResult.status());
        var resultTree = mapper.readTree(mapper.writeValueAsString(capabilityResult.body()));
        assertEquals("SUCCEEDED", resultTree.path("status").asText(),
                () -> "capability result: " + capabilityResult.body());
        String capabilityJson = mapper.writeValueAsString(capabilityResult.body());
        assertTrue(resultTree.has("content"));
        assertTrue(resultTree.path("content").has("candidates"));

        var assistant = new GaModelContract.Message("assistant", "", List.of(call), null);
        var tool = new GaModelContract.Message("tool", capabilityJson, List.of(), call.id());
        var secondRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId, List.of(user, assistant, tool),
                binding.allowedTools(), "none", 256, false);
        var second = broker.complete(secondRequest);

        assertEquals("responses final answer", second.content());
        assertEquals(2, provider.requests.size());
        var followUp = provider.requests.get(1);
        var historyCall = followUp.path("input").get(1);
        assertEquals("function_call", historyCall.path("type").asText());
        assertEquals(call.id(), historyCall.path("call_id").asText());
        assertEquals(descriptor.name(), historyCall.path("name").asText());
        assertFalse(historyCall.has("role"));
        assertFalse(historyCall.has("tool_calls"));
        var outputItem = followUp.path("input").get(2);
        assertEquals("function_call_output", outputItem.path("type").asText());
        assertEquals(call.id(), outputItem.path("call_id").asText());
        assertFalse(followUp.has("previous_response_id"));

        var modelCalls = jdbc.queryForList(
                "SELECT call_id,status FROM ga_execution_calls WHERE run_id=? AND kind='MODEL'", run);
        assertEquals(2, modelCalls.size());
        assertTrue(modelCalls.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status"))));
        var toolCalls = jdbc.queryForList(
                "SELECT call_id,status FROM ga_execution_calls WHERE run_id=? AND kind='TOOL'", run);
        assertEquals(1, toolCalls.size());
        assertEquals("SUCCEEDED", toolCalls.getFirst().get("status"));
    }

    @Test
    void repeatedModelCallIdReplaysLedgerWithoutProviderTraffic() throws Exception {
        var user = new GaModelContract.Message("user", "search projects", List.of(), null);
        var callId = UUID.randomUUID();
        var firstRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                callId, "AGENT", bindingId, List.of(user), binding.allowedTools(),
                "required", 256, false);
        var first = broker.complete(firstRequest);
        int requestsAfterFirst = provider.requests.size();

        var replay = broker.complete(firstRequest);

        assertEquals(first.toolCalls().getFirst().id(), replay.toolCalls().getFirst().id());
        assertEquals(requestsAfterFirst, provider.requests.size());
    }
}
