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
import com.specagent.model.provider.AnthropicGaNativeTransport;
import com.specagent.model.provider.RoutingGaNativeInferenceGateway;
import com.specagent.support.FakeAnthropicMessagesProvider;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MS-C3 production-path closed loop for Custom Anthropic Messages: Python-v2
 * shaped requests through the Java broker, the strict Fake Anthropic provider,
 * the MODEL/TOOL ledgers, the Java capability runtime and the second
 * tool_result request. Mixed text+tool history, thinking boundaries and
 * idempotent replay are all exercised on the production transport.
 */
@SpringBootTest
@ActiveProfiles("test")
class GaAnthropicNativeToolEndToEndTest {
    @Autowired GaExecutionStore executions;
    @Autowired GaModelContinuationStore continuations;
    @Autowired GaCatalogProjection catalogs;
    @Autowired GaCapabilityBroker capabilities;
    @Autowired ConversationDeleteService deletion;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    FakeAnthropicMessagesProvider provider;
    GaNativeModelBroker broker;
    UUID thread, run, lease, bindingId;
    GaExecutionStore.Scope scope;
    GaExecutionStore.Binding binding;
    GaCatalogProjection.Descriptor descriptor;
    GaModelContract.Tool searchTool;

    @BeforeEach
    void fixture() throws Exception {
        provider = new FakeAnthropicMessagesProvider();
        descriptor = catalogs.current().descriptors().stream()
                .filter(item -> "project.search".equals(item.capabilityId()))
                .findFirst().orElseThrow();
        searchTool = catalogs.current().tools().stream()
                .filter(item -> item.name().equals(descriptor.name()))
                .findFirst().orElseThrow();
        provider.toolName = descriptor.name();
        provider.toolArguments = "{\"query\":\"spec agent\"}";
        provider.toolShape = FakeAnthropicMessagesProvider.ToolShape.SINGLE;
        UUID providerId = UUID.randomUUID();
        RuntimeCustomSettings settings = new RuntimeCustomSettings("ANTHROPIC_MESSAGES",
                provider.baseUrl(), "fixture-secret", "anthropic-model", 1, providerId, true);
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
        var anthropic = new AnthropicGaNativeTransport(port, mapper,
                java.net.http.HttpClient.newHttpClient());
        var routing = new RoutingGaNativeInferenceGateway(
                org.mockito.Mockito.mock(com.specagent.model.provider.OpenCodeZenTransport.class),
                org.mockito.Mockito.mock(com.specagent.model.provider.KiloGaNativeTransport.class),
                org.mockito.Mockito.mock(com.specagent.model.provider.ChatCompletionsGaTextTransport.class),
                org.mockito.Mockito.mock(com.specagent.model.provider.ResponsesGaNativeTransport.class),
                anthropic, port);
        var resolver = org.mockito.Mockito.mock(GaExecutionModelTargetResolver.class);
        org.mockito.Mockito.when(resolver.requireFrozen("CUSTOM", "anthropic-model", settings.bindingRevision()))
                .thenReturn(new GaModelTarget("CUSTOM", "anthropic-model",
                        settings.bindingRevision(), "fixture-secret"));
        broker = new GaNativeModelBroker(executions, resolver, routing, continuations);

        thread = conversations.createThread().id();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        lease = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        scope = new GaExecutionStore.Scope(run, 1, lease);
        binding = new GaExecutionStore.Binding(thread, bindingId, "CUSTOM", "anthropic-model",
                settings.bindingRevision(), Instant.now().plusSeconds(120), List.of(searchTool));
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

    private GaModelContract.Request firstRound() {
        var user = new GaModelContract.Message("user", "search projects", List.of(), null);
        return new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId, List.of(user), binding.allowedTools(),
                "required", 256, false);
    }

    private String invokeCapability(GaModelContract.ToolCall call) throws Exception {
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
        return mapper.writeValueAsString(capabilityResult.body());
    }

    @Test
    void anthropicToolUseRoundTripExecutesOnlyThroughJavaCapabilityRuntime() throws Exception {
        var first = broker.complete(firstRound());

        assertEquals("tool_calls", first.finishReason());
        assertEquals(1, first.toolCalls().size());
        var call = first.toolCalls().getFirst();
        assertEquals(descriptor.name(), call.name());
        assertEquals(Map.of("query", "spec agent"), call.arguments());
        // tool_use.id 原样保留,宿主绝不生成新 ToolCall ID。
        assertEquals("toolu_01", call.id());
        // v2 线协议绝不携带 Anthropic 专属字段或私有内容;原始 tool_use.id
        // (toolu_*)按协议要求原样保留并由 Python 在 tool_result 中回传。
        String wire = mapper.writeValueAsString(first);
        assertTrue(wire.contains("\"toolCalls\":[{"));
        assertTrue(!wire.contains("anthropic") && !wire.contains("thinking"),
                "wire must stay provider neutral: " + wire);

        String capabilityJson = invokeCapability(call);
        var resultTree = mapper.readTree(capabilityJson);
        assertEquals("SUCCEEDED", resultTree.path("status").asText(),
                () -> "capability result: " + capabilityJson);

        var assistant = new GaModelContract.Message("assistant", "", List.of(call), null);
        var tool = new GaModelContract.Message("tool", capabilityJson, List.of(), call.id());
        var secondRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId,
                List.of(new GaModelContract.Message("user", "search projects", List.of(), null),
                        assistant, tool),
                binding.allowedTools(), "none", 256, false);
        var second = broker.complete(secondRequest);

        assertEquals("anthropic final answer", second.content());
        assertEquals(2, provider.requests.size());
        var followUp = provider.requests.get(1);
        var assistantHistory = followUp.path("messages").get(1);
        assertEquals("assistant", assistantHistory.path("role").asText());
        assertEquals("tool_use", assistantHistory.path("content").get(0).path("type").asText());
        assertEquals(call.id(), assistantHistory.path("content").get(0).path("id").asText());
        assertTrue(!assistantHistory.has("tool_calls") && !assistantHistory.has("function_call"),
                "history must use native tool_use blocks");
        var userResult = followUp.path("messages").get(2);
        assertEquals("user", userResult.path("role").asText());
        assertEquals("tool_result", userResult.path("content").get(0).path("type").asText());
        assertEquals(call.id(), userResult.path("content").get(0).path("tool_use_id").asText());
        assertTrue(!userResult.path("content").get(0).has("function_call_output"),
                "tool results are never responses function_call_output");

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
    void mixedTextThenToolUseContinuesFullyAcrossBothRounds() throws Exception {
        provider.toolShape = FakeAnthropicMessagesProvider.ToolShape.TEXT_THEN_TOOL;
        var first = broker.complete(firstRound());

        assertEquals("tool_calls", first.finishReason());
        assertEquals("I will search first.", first.content());
        var call = first.toolCalls().getFirst();
        String capabilityJson = invokeCapability(call);

        var assistant = new GaModelContract.Message("assistant", first.content(),
                List.of(call), null);
        var tool = new GaModelContract.Message("tool", capabilityJson, List.of(), call.id());
        var secondRequest = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                UUID.randomUUID(), "AGENT", bindingId,
                List.of(new GaModelContract.Message("user", "search projects", List.of(), null),
                        assistant, tool),
                binding.allowedTools(), "none", 256, false);
        var second = broker.complete(secondRequest);

        assertEquals("anthropic final answer", second.content());
        var history = provider.requests.get(1).path("messages").get(1).path("content");
        assertEquals("text", history.get(0).path("type").asText());
        assertEquals("I will search first.", history.get(0).path("text").asText());
        assertEquals("tool_use", history.get(1).path("type").asText());
        assertEquals("tool_result", provider.requests.get(1).path("messages")
                .get(2).path("content").get(0).path("type").asText());
        var modelCalls = jdbc.queryForList(
                "SELECT status FROM ga_execution_calls WHERE run_id=? AND kind='MODEL'", run);
        assertEquals(2, modelCalls.size());
        assertTrue(modelCalls.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status"))));
    }

    @Test
    void toolUseFollowedByTextIsRejectedBeforeAnyToolExecutionOrLedgerSuccess() throws Exception {
        provider.toolShape = FakeAnthropicMessagesProvider.ToolShape.TOOL_THEN_TEXT;
        assertThrows(IllegalArgumentException.class, () -> broker.complete(firstRound()));

        // 拒绝发生在第一轮解析:没有第二次 Provider 请求,没有 TOOL 账本,MODEL 未 SUCCEEDED。
        assertEquals(1, provider.requests.size());
        var modelCalls = jdbc.queryForList(
                "SELECT call_id,status FROM ga_execution_calls WHERE run_id=? AND kind='MODEL'", run);
        assertEquals(1, modelCalls.size());
        assertNotEquals("SUCCEEDED", modelCalls.getFirst().get("status"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL'",
                Integer.class, run));
    }

    @Test
    void thinkingPlusToolUseIsRejectedBeforeRelease() throws Exception {
        provider.toolShape = FakeAnthropicMessagesProvider.ToolShape.THINKING_THEN_TOOL;
        assertThrows(IllegalArgumentException.class, () -> broker.complete(firstRound()));

        assertEquals(1, provider.requests.size());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM ga_execution_calls WHERE run_id=? AND kind='TOOL'",
                Integer.class, run));
        var modelCalls = jdbc.queryForList(
                "SELECT status FROM ga_execution_calls WHERE run_id=? AND kind='MODEL'", run);
        assertEquals(1, modelCalls.size());
        assertNotEquals("SUCCEEDED", modelCalls.getFirst().get("status"));
    }

    @Test
    void repeatedModelCallIdReplaysLedgerWithoutProviderTraffic() throws Exception {
        var callId = UUID.randomUUID();
        var request = new GaModelContract.Request(GaModelContract.VERSION_2, run, 1, lease,
                callId, "AGENT", bindingId,
                List.of(new GaModelContract.Message("user", "search projects", List.of(), null)),
                binding.allowedTools(), "required", 256, false);
        var first = broker.complete(request);
        int requestsAfterFirst = provider.requests.size();

        var replay = broker.complete(request);

        assertEquals(first.toolCalls().getFirst().id(), replay.toolCalls().getFirst().id());
        assertEquals(requestsAfterFirst, provider.requests.size());
    }
}
