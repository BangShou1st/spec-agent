package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaCheckpointStore;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.assistant.runtime.GaModelContinuationStore;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.model.contract.GaModelContract;
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

@SpringBootTest
@ActiveProfiles("test")
class GaModelContinuationIntegrationTest {
    @Autowired GaExecutionStore executions;
    @Autowired GaModelContinuationStore continuations;
    @Autowired GaCheckpointStore checkpoints;
    @Autowired ConversationDeleteService deletion;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    ObjectMapper mapper = new ObjectMapper();
    UUID thread, run, lease, bindingId, callId;
    GaExecutionStore.Scope scope;
    GaExecutionStore.Binding binding;

    @BeforeEach void fixture() {
        thread = conversations.createThread().id();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        lease = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        callId = UUID.randomUUID();
        scope = new GaExecutionStore.Scope(run, 1, lease);
        binding = new GaExecutionStore.Binding(thread, bindingId, "CUSTOM", "gemini-test",
                "custom-provider:" + UUID.randomUUID(), Instant.now().plusSeconds(120), List.of());
        executions.initialize(scope, binding);
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
    }

    @AfterEach void cleanup() {
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        try { deletion.deleteThread(thread); }
        catch (RuntimeException alreadyGone) { /* test-owned physical cleanup path may already have removed it */ }
    }

    private Map<String, Object> signature() {
        return Map.of("google", Map.of("thought_signature", "sig_" + UUID.randomUUID()));
    }

    @Test void sidecarIsEncryptedBoundToOwnerAndResolvable() throws Exception {
        var state = signature();
        executions.reserve(scope, callId, "MODEL", Hashes.sha256Hex("source"));
        executions.complete(scope, callId, "{}");
        var ref = continuations.persist(new GaModelContinuationStore.Source(
                jdbc.queryForObject("SELECT org_id FROM global_assistant_threads WHERE id=?", UUID.class, thread),
                thread, run, callId), binding, callId, List.of("call-1"), state);
        assertTrue(ref.matches("gacr-[0-9a-f-]{36}"));
        var stored = jdbc.queryForMap("SELECT payload, tool_call_ids, payload_length FROM ga_model_continuations WHERE id=?",
                UUID.fromString(ref.substring("gacr-".length())));
        assertFalse(stored.get("payload").toString().contains("sig_"));
        assertEquals("[\"call-1\"]", stored.get("tool_call_ids"));
        var resolved = continuations.resolve(ref, scope, binding);
        assertEquals(state, resolved.state());
        assertEquals(List.of("call-1"), resolved.toolCallIds());
        // Idempotent resolve under the same active scope.
        assertEquals(resolved.sourceCallId(), continuations.resolve(ref, scope, binding).sourceCallId());
    }

    @Test void resolveFailsClosedAcrossThreadBindingAndSource() throws Exception {
        executions.reserve(scope, callId, "MODEL", Hashes.sha256Hex("source"));
        executions.complete(scope, callId, "{}");
        var ref = continuations.persist(new GaModelContinuationStore.Source(
                jdbc.queryForObject("SELECT org_id FROM global_assistant_threads WHERE id=?", UUID.class, thread),
                thread, run, callId), binding, callId, List.of("call-1"), signature());
        var otherThread = conversations.createThread().id();
        var otherRun = conversations.createRun(otherThread, "ga.v1", "ga.v1", "v1").id();
        var otherScope = new GaExecutionStore.Scope(otherRun, 1, UUID.randomUUID());
        try {
            executions.initialize(otherScope, new GaExecutionStore.Binding(otherThread, UUID.randomUUID(),
                    "CUSTOM", "gemini-test", binding.settingsRevision(), Instant.now().plusSeconds(120), List.of()));
            jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", otherRun);
            assertThrows(RuntimeException.class,
                    () -> continuations.resolve(ref, otherScope, binding));
        } finally {
            jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", otherRun);
            deletion.deleteThread(otherThread);
        }
    }

    @Test void migrationRequiresRecordedSuccessfulLedgerAndIsIdempotent() throws Exception {
        var state = signature();
        var toolIds = List.of("native-1");
        var call = new GaModelContract.ToolCall("native-1", "project_search", Map.of("query", "x"), state);
        var legacy = new GaModelContract.Response(GaModelContract.VERSION, "", List.of(call),
                "tool_calls", new GaModelContract.Usage(1, 1));
        executions.reserve(scope, callId, "MODEL", Hashes.sha256Hex("legacy"));
        executions.complete(scope, callId, mapper.writeValueAsString(legacy));
        var request = migrationRequest(toolIds, state);
        var first = checkpoints.apply("MIGRATE_CONTINUATION", mapper.writeValueAsString(request));
        var ref = ((Map<?, ?>) first.get("result")).get("continuationRef").toString();
        assertTrue(ref.matches("gacr-[0-9a-f-]{36}"));
        var second = checkpoints.apply("MIGRATE_CONTINUATION", mapper.writeValueAsString(request));
        assertEquals(ref, ((Map<?, ?>) second.get("result")).get("continuationRef").toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ga_model_continuations WHERE source_call_id=?", Integer.class, callId));

        var forged = migrationRequest(toolIds, Map.of("google", Map.of("thought_signature", "forged")));
        assertThrows(RuntimeException.class,
                () -> checkpoints.apply("MIGRATE_CONTINUATION", mapper.writeValueAsString(forged)));
    }

    @Test void staleEpochAndTamperedToolIdentityFailClosed() throws Exception {
        executions.reserve(scope, callId, "MODEL", Hashes.sha256Hex("source"));
        executions.complete(scope, callId, "{}");
        var ref = continuations.persist(new GaModelContinuationStore.Source(
                jdbc.queryForObject("SELECT org_id FROM global_assistant_threads WHERE id=?", UUID.class, thread),
                thread, run, callId), binding, callId, List.of("call-1"), signature());
        var stale = new GaExecutionStore.Scope(run, 2, lease);
        assertThrows(RuntimeException.class, () -> continuations.resolve(ref, stale, binding));
        // Tool identity tampering is rejected by the broker-level expected-id check.
        var resolved = continuations.resolve(ref, scope, binding);
        assertNotEquals(List.of("tampered"), resolved.toolCallIds());
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?", run);
        assertThrows(RuntimeException.class, () -> continuations.resolve(ref, scope, binding));
    }

    @Test void threadDeletionRemovesContinuationSidecar() throws Exception {
        executions.reserve(scope, callId, "MODEL", Hashes.sha256Hex("source"));
        executions.complete(scope, callId, "{}");
        var ref = continuations.persist(new GaModelContinuationStore.Source(
                jdbc.queryForObject("SELECT org_id FROM global_assistant_threads WHERE id=?", UUID.class, thread),
                thread, run, callId), binding, callId, List.of("call-1"), signature());
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
        jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id=?", thread);
        jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id=?", thread);
        jdbc.update("DELETE FROM global_assistant_threads WHERE id=?", thread);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ga_model_continuations", Integer.class));
    }

    private Map<String, Object> migrationRequest(List<String> toolIds, Map<String, Object> state) {
        return Maps.of("protocolVersion", "ga-checkpoint.v1", "runId", run.toString(),
                "executionEpoch", 1, "leaseId", lease.toString(), "threadId", thread.toString(),
                "namespace", "ga:langchain-ga.v1", "expectedVersion", 0,
                "toolCallIds", toolIds, "continuationPayload", mapper.valueToTree(state).toString());
    }
}


