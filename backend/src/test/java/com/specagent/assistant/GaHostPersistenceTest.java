package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.runtime.GaCheckpointStore;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GaHostPersistenceTest {
    @Autowired GaExecutionStore executions;
    @Autowired GaCheckpointStore checkpoints;
    @Autowired ConversationDeleteService deletion;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    UUID thread, run, lease;
    GaExecutionStore.Scope scope;

    @BeforeEach void fixture() {
        thread = UUID.randomUUID(); run = UUID.randomUUID(); lease = UUID.randomUUID();
        jdbc.update("INSERT INTO global_assistant_threads(id) VALUES(?)", thread);
        jdbc.update("INSERT INTO global_assistant_runs(id,thread_id,status) VALUES(?,?,'CREATED')", run, thread);
        scope = new GaExecutionStore.Scope(run, 1, lease);
        executions.initialize(scope, new GaExecutionStore.Binding(thread, UUID.randomUUID(), "OPENCODE_ZEN", "test-model",
                "test-revision", Instant.now().plusSeconds(120), List.of()));
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
    }

    @Test void modelBudgetIsDurableAndDuplicateReservationDoesNotResetOrConsumeIt() {
        UUID first = UUID.randomUUID(); String hash = Hashes.sha256Hex("request");
        assertTrue(executions.reserve(scope, first, "MODEL", hash).fresh());
        assertFalse(executions.reserve(scope, first, "MODEL", hash).fresh());
        assertThrows(IllegalStateException.class, () -> executions.reserve(scope, first, "MODEL", Hashes.sha256Hex("changed")));
        for (int i=0; i<5; i++) executions.reserve(scope, UUID.randomUUID(), "MODEL", hash);
        assertThrows(IllegalStateException.class, () -> executions.reserve(scope, UUID.randomUUID(), "MODEL", hash));
        assertEquals(6, jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?", Integer.class, run));
        executions.unknown(scope, first);
        assertEquals("UNKNOWN", executions.reserve(scope, first, "MODEL", hash).status());
    }

    @Test void concurrentLocalMutationExecutesOnceAndCommitsWithLedger() throws Exception {
        UUID call = UUID.randomUUID(); String hash = Hashes.sha256Hex("local-mutation");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i=0; i<4; i++) results.add(pool.submit(() -> {
                start.await();
                return executions.localTool(scope, call, hash, () -> {
                    jdbc.update("UPDATE global_assistant_threads SET summary_version=summary_version+1 WHERE id=?", thread);
                    return "{\"result\":\"created\"}";
                });
            }));
            start.countDown();
            for (var result : results) assertEquals("{\"result\":\"created\"}", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT summary_version FROM global_assistant_threads WHERE id=?", Integer.class, thread));
            assertEquals(1, jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?", Integer.class, run));
        } finally { pool.shutdownNow(); }
    }

    @Test void failedLocalMutationRollsBackBusinessChangeLedgerAndBudget() {
        assertThrows(IllegalStateException.class, () -> executions.localTool(scope, UUID.randomUUID(), Hashes.sha256Hex("fail"), () -> {
            jdbc.update("UPDATE global_assistant_threads SET summary_version=summary_version+1 WHERE id=?", thread);
            throw new IllegalStateException("local-failure");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT summary_version FROM global_assistant_threads WHERE id=?", Integer.class, thread));
        assertEquals(0, jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?", Integer.class, run));
    }

    @Test void staleEpochLeaseCancelDeadlineAndLateCompletionAreFenced() {
        assertFalse(executions.active(new GaExecutionStore.Scope(run, 2, lease)));
        assertThrows(IllegalStateException.class, () -> executions.authorize(new GaExecutionStore.Scope(run, 1, UUID.randomUUID())));
        UUID call = UUID.randomUUID(); executions.reserve(scope, call, "MODEL", Hashes.sha256Hex("call"));
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=now() WHERE id=?", run);
        assertFalse(executions.active(scope));
        assertThrows(IllegalStateException.class, () -> executions.complete(scope, call, "late"));
        assertThrows(IllegalStateException.class, () -> executions.localTool(scope, UUID.randomUUID(), Hashes.sha256Hex("tool"), () -> fail("cancelled tool ran")));
        jdbc.update("UPDATE global_assistant_runs SET cancel_requested_at=NULL WHERE id=?", run);
        jdbc.update("UPDATE ga_executions SET deadline=now()-interval '1 second' WHERE run_id=?", run);
        assertFalse(executions.active(scope));
        assertThrows(IllegalStateException.class, () -> executions.authorize(scope));
    }

    @Test void checkpointCasDuplicatePutIntermediateWritesAndDeletionPersist() throws Exception {
        Map<String,Object> put = request(0);
        put.putAll(Maps.of("checkpointId", "cp1", "parentCheckpointId", null,
                "checkpoint", envelope(Map.of("tag", "dict", "value", Map.of("id", "cp1"))),
                "metadata", envelope(Map.of("tag", "dict", "value", Map.of("step", 1))),
                "newVersions", envelope(Map.of("tag", "dict", "value", Map.of()))));
        assertEquals(1L, apply("PUT", put).get("version"));
        assertEquals(1L, apply("PUT", put).get("version")); // Lost-response replay is not another mutation.
        var stale = new LinkedHashMap<>(put);
        stale.put("checkpointId", "cp2"); stale.put("checkpoint", envelope(Map.of("tag", "dict", "value", Map.of("id", "cp2"))));
        assertThrows(IllegalStateException.class, () -> apply("PUT", stale));
        var write = request(1); write.putAll(Maps.of("checkpointId", "cp1", "taskId", "task1", "taskPath", "",
                "writes", List.of(Map.of("index", 0, "channel", "messages", "value", envelope("hello")))));
        assertEquals(2L, apply("PUT_WRITES", write).get("version"));
        assertEquals(2L, apply("PUT_WRITES", write).get("version"));
        var get = request(0); get.put("checkpointId", null);
        var tuple = (Map<?,?>) apply("GET", get).get("result");
        var writes = (Map<?,?>) tuple.get("pendingWrites");
        assertTrue(writes.get("payload").toString().contains("task1"));
        assertEquals(Hashes.sha256Hex((String) writes.get("payload")), writes.get("payloadHash"));
        var list = request(2); list.putAll(Maps.of("before", null, "limit", 10, "filter", Map.of("step", 1)));
        assertEquals(1, ((List<?>) apply("LIST", list).get("result")).size());
        var wrong = request(2); wrong.put("checkpointId", null); wrong.put("threadId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> apply("GET", wrong));
        assertEquals(3L, apply("DELETE_THREAD", request(2)).get("version"));
        assertNull(apply("GET", get).get("result"));
    }

    @Test void checkpointRejectsCorruptHashUnknownFieldsAndCodec() throws Exception {
        var get = request(0); get.put("checkpointId", null); get.put("sql", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> apply("GET", get));
        var put = request(0);
        var corrupt = envelope(Map.of("tag", "dict", "value", Map.of("id", "cp1")));
        corrupt.put("payloadHash", "0".repeat(64));
        put.putAll(Maps.of("checkpointId", "cp1", "parentCheckpointId", null, "checkpoint", corrupt,
                "metadata", envelope(null), "newVersions", envelope(null)));
        assertThrows(IllegalArgumentException.class, () -> apply("PUT", put));
        corrupt.put("codecVersion", "pickle");
        assertThrows(IllegalArgumentException.class, () -> apply("PUT", put));
    }

    private Map<String,Object> request(long version) {
        return Maps.of("protocolVersion", "ga-checkpoint.v1", "runId", run.toString(), "executionEpoch", 1,
                "leaseId", lease.toString(), "threadId", thread.toString(), "namespace", "ga:langchain-ga.v1", "expectedVersion", version);
    }
    private Map<String,Object> envelope(Object value) throws Exception {
        String payload = mapper.writeValueAsString(value);
        return Maps.of("codecVersion", "ga-json.v1", "frameworkVersion", "langgraph-1.2.12/checkpoint-4.2.0",
                "stateVersion", "langchain-ga.v1", "payload", payload, "payloadHash", Hashes.sha256Hex(payload));
    }
    private Map<String,Object> apply(String operation, Map<String,Object> request) throws Exception {
        return checkpoints.apply(operation, mapper.writeValueAsString(request));
    }
}
