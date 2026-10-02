package com.specagent.assistant.runtime;

import com.specagent.common.Maps;
import com.specagent.model.contract.GaModelContract;
import com.specagent.assistant.tool.GaCatalogProjection;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Host execution fence and durable budget. No Agent loop or provider credentials. */
@Service
public class GaExecutionStore {
    public record Scope(UUID runId, long epoch, UUID leaseId) {}
    public record Binding(UUID threadId, UUID modelBindingId, String provider, String model,
                          String settingsRevision, Instant deadline, java.util.List<GaModelContract.Tool> allowedTools) {
        public Binding { allowedTools = java.util.List.copyOf(allowedTools); }
    }
    public record Reservation(boolean fresh, String status, String result) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final GaCatalogProjection catalogs;
    public GaExecutionStore(NamedParameterJdbcTemplate jdbc, GaCatalogProjection catalogs) { this.jdbc = jdbc; this.catalogs=catalogs; }

    /** Called only by the explicit coordinator, before lifecycle claim. Never rebinds an existing run. */
    @Transactional
    public void initialize(Scope scope, Binding binding) {
        initialize(scope,binding,java.util.List.of());
    }

    @Transactional
    public void initialize(Scope scope, Binding binding, java.util.List<GaCatalogProjection.Descriptor> descriptors) {
        var catalog=catalogs.snapshot(descriptors);
        if (!descriptors.isEmpty() && !catalog.tools().equals(binding.allowedTools()))
            throw new IllegalArgumentException("GA model and capability projections differ");
        if (scope.runId() == null || scope.epoch() != 1 || scope.leaseId() == null
                || binding.threadId() == null || binding.modelBindingId() == null
                || !"OPENCODE_ZEN".equals(binding.provider()) || binding.model() == null || binding.model().isBlank()
                || binding.settingsRevision() == null || binding.settingsRevision().isBlank()
                || binding.allowedTools().size() > 12
                || binding.deadline() == null || !binding.deadline().isAfter(Instant.now())
                || binding.deadline().isAfter(Instant.now().plusSeconds(180)))
            throw new IllegalArgumentException("Invalid GA execution binding");
        Map<String, Object> run = lockRun(scope.runId());
        if (!"CREATED".equals(run.get("status")) || run.get("cancel_requested_at") != null
                || !binding.threadId().equals(run.get("thread_id")) || !"java-legacy.v1".equals(run.get("engine_version")))
            throw new IllegalStateException("GA_EXECUTION_FENCE");
        var params = Maps.of("run", scope.runId(), "thread", binding.threadId(), "binding", binding.modelBindingId(),
                "provider", binding.provider(), "model", binding.model(), "revision", binding.settingsRevision(),
                "epoch", scope.epoch(), "lease", scope.leaseId(), "deadline", Timestamp.from(binding.deadline()),
                "tools", toolsJson(binding.allowedTools()));
        params.putAll(Maps.of("catalog",catalogs.canonical(descriptors),"catalogHash",catalog.hash()));
        jdbc.update("""
                INSERT INTO ga_executions (run_id,thread_id,model_binding_id,provider,model,settings_revision,
                  execution_epoch,lease_id,deadline,allowed_tools,capability_catalog,catalog_hash)
                VALUES (:run,:thread,:binding,:provider,:model,:revision,:epoch,:lease,:deadline,:tools,:catalog,:catalogHash)
                """, params);
        jdbc.update("UPDATE global_assistant_runs SET engine_version='langchain-ga.v1' WHERE id=:run", params);
        jdbc.update("INSERT INTO ga_checkpoint_heads(thread_id) VALUES (:thread) ON CONFLICT DO NOTHING", params);
    }

    @Transactional
    public Binding authorize(Scope scope) { return lockedBinding(scope); }

    @Transactional
    public Object claimExecutor(String body) {
        try {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var value=mapper.readTree(body);
            var keys=new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(java.util.Set.of("runId","executionEpoch","leaseId","executionRequestHash"))
                    || !value.path("executionEpoch").isIntegralNumber() || !value.path("executionEpoch").canConvertToLong()
                    || value.path("executionEpoch").longValue()<1) throw new IllegalArgumentException();
            var scope=new Scope(UUID.fromString(value.path("runId").textValue()),value.path("executionEpoch").longValue(),
                    UUID.fromString(value.path("leaseId").textValue()));
            var binding=lockedBinding(scope);
            var params=params(scope); params.put("hash",value.path("executionRequestHash").textValue());
            if(jdbc.update("UPDATE ga_executions SET executor_claimed=TRUE WHERE run_id=:run AND execution_request_hash=:hash AND executor_claimed=FALSE",params)!=1)
                throw new IllegalStateException("GA_EXECUTOR_ALREADY_CLAIMED");
            return Maps.of("checkpointId",jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=:thread",
                    Map.of("thread",binding.threadId()),String.class));
        } catch(IllegalStateException ex) { throw ex; }
        catch(Exception ex) { throw new IllegalArgumentException("GA execution claim protocol"); }
    }

    /** Read-only polling guard; false also fences missing/deleted/terminal runs. */
    public boolean active(Scope scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ga_executions e JOIN global_assistant_runs r ON r.id=e.run_id
                WHERE e.run_id=:run AND e.execution_epoch=:epoch AND e.lease_id=:lease AND e.deadline>clock_timestamp()
                  AND r.engine_version='langchain-ga.v1' AND r.status='RUNNING' AND r.cancel_requested_at IS NULL)
                """, params(scope), Boolean.class));
    }

    @Transactional
    public Reservation reserve(Scope scope, UUID call, String kind, String hash) {
        lockedBinding(scope);
        if (call == null || !java.util.Set.of("MODEL", "TOOL").contains(kind)
                || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid GA call");
        var params = params(scope); params.putAll(Maps.of("call", call, "kind", kind, "hash", hash));
        var existing = jdbc.queryForList("SELECT * FROM ga_execution_calls WHERE run_id=:run AND call_id=:call", params);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!kind.equals(row.get("kind")) || !hash.equals(row.get("payload_hash")))
                throw new IllegalStateException("GA_CALL_ID_CONFLICT");
            return new Reservation(false, (String) row.get("status"), (String) row.get("result"));
        }
        String column = "MODEL".equals(kind) ? "model_calls" : "tool_calls";
        int limit = "MODEL".equals(kind) ? 6 : 5;
        if (jdbc.update("UPDATE ga_executions SET " + column + "=" + column + "+1 WHERE run_id=:run AND "
                + column + "<" + limit, params) != 1) throw new IllegalStateException("GA_BUDGET_EXHAUSTED");
        jdbc.update("INSERT INTO ga_execution_calls(run_id,call_id,kind,payload_hash,status) VALUES(:run,:call,:kind,:hash,'RESERVED')", params);
        return new Reservation(true, "RESERVED", null);
    }

    @Transactional
    public void complete(Scope scope, UUID call, String result) {
        lockedBinding(scope); // Late output cannot commit after cancellation or terminalization.
        if (result == null || result.getBytes(StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("GA result limit exceeded");
        var params = params(scope); params.putAll(Maps.of("call", call, "result", result));
        if (jdbc.update("UPDATE ga_execution_calls SET status='SUCCEEDED',result=:result WHERE run_id=:run AND call_id=:call AND status='RESERVED'", params) != 1)
            throw new IllegalStateException("GA_CALL_NOT_RESERVED");
    }

    /** Failed/uncertain network calls consume budget and cannot be reissued. */
    @Transactional
    public void unknown(Scope scope, UUID call) {
        lockRun(scope.runId());
        var params = params(scope); params.put("call", call);
        jdbc.update("""
                UPDATE ga_execution_calls SET status='UNKNOWN' WHERE run_id=:run AND call_id=:call AND status='RESERVED'
                AND EXISTS(SELECT 1 FROM ga_executions WHERE run_id=:run AND execution_epoch=:epoch AND lease_id=:lease)
                """, params);
    }

    /** Executes a host-local transactional mutation behind the same cancellation fence and call ledger. */
    @Transactional
    public String localTool(Scope scope, UUID call, String hash, java.util.function.Supplier<String> action) {
        var reservation = reserve(scope, call, "TOOL", hash);
        if (!reservation.fresh()) {
            if ("SUCCEEDED".equals(reservation.status())) return reservation.result();
            throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
        }
        String result = action.get();
        complete(scope, call, result);
        return result;
    }

    /** Reserve is committed before this transaction for tools with non-database staging effects. */
    @Transactional
    public void beginReservedTool(Scope scope, UUID call, Runnable publishStarted) {
        lockedBinding(scope);
        var parameters = params(scope); parameters.put("call", call);
        if (jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=:run AND call_id=:call AND kind='TOOL' AND status='RESERVED'",
                parameters, Integer.class) != 1) throw new IllegalStateException("GA_CALL_NOT_RESERVED");
        publishStarted.run();
    }

    @Transactional
    public String performReservedTool(Scope scope, UUID call, java.util.function.Supplier<String> action) {
        lockedBinding(scope);
        var params=params(scope); params.put("call",call);
        var rows=jdbc.queryForList("SELECT status FROM ga_execution_calls WHERE run_id=:run AND call_id=:call AND kind='TOOL'",params);
        if (rows.size()!=1 || !"RESERVED".equals(rows.getFirst().get("status")))
            throw new IllegalStateException("GA_CALL_NOT_RESERVED");
        String result=action.get(); complete(scope,call,result); return result;
    }

    @Transactional
    public GaCatalogProjection.Snapshot catalog(Scope scope) {
        var binding=lockedBinding(scope);
        var row=jdbc.queryForMap("SELECT capability_catalog,catalog_hash FROM ga_executions WHERE run_id=:run",params(scope));
        String json=(String)row.get("capability_catalog");
        try {
            var descriptors=new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<GaCatalogProjection.Descriptor>>() {});
            var snapshot=catalogs.snapshot(descriptors);
            if (!snapshot.hash().equals(row.get("catalog_hash")) || !snapshot.tools().equals(binding.allowedTools()))
                throw new IllegalStateException("Invalid GA catalog snapshot");
            return snapshot;
        } catch (Exception ex) { throw new IllegalStateException("Invalid GA catalog snapshot"); }
    }

    /** Capability dispatch must correspond to a persisted successful native model output. */
    public boolean recordedTool(Scope scope, String id, String name, String argumentsJson) {
        for (String json : jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=:run AND kind='MODEL' AND status='SUCCEEDED'",params(scope),String.class)) {
            var result=GaModelContract.readResponse(json);
            for (var call : result.toolCalls()) {
                if (id.equals(call.id()) && name.equals(call.name()) && argumentsJson.equals(catalogs.canonical(call.arguments()))) return true;
            }
        }
        return false;
    }

    public boolean repeatedToolWithoutNewObservation(Scope scope, String name, String argumentsJson) {
        var rows=jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=:run AND kind='TOOL' AND status='SUCCEEDED' ORDER BY call_sequence DESC LIMIT 1",params(scope),String.class);
        if (rows.isEmpty()) return false;
        try {
            String previousId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getFirst()).path("toolCallId").textValue();
            return previousId!=null && recordedTool(scope,previousId,name,argumentsJson);
        } catch (Exception ex) { throw new IllegalStateException("Invalid GA tool observation"); }
    }

    public boolean uncertainTool(Scope scope, UUID current, String name, String argumentsJson) {
        var params=params(scope); params.put("current",current);
        var uncertain=new java.util.HashSet<>(jdbc.queryForList("SELECT call_id FROM ga_execution_calls WHERE run_id=:run AND kind='TOOL' AND status IN ('RESERVED','UNKNOWN') AND call_id<>:current",params,UUID.class));
        if (uncertain.isEmpty()) return false;
        for (String json : jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=:run AND kind='MODEL' AND status='SUCCEEDED'",params,String.class)) {
            for (var call : GaModelContract.readResponse(json).toolCalls()) {
                UUID identity=UUID.nameUUIDFromBytes((scope.runId()+":"+call.id()).getBytes(StandardCharsets.UTF_8));
                if (uncertain.contains(identity) && name.equals(call.name()) && argumentsJson.equals(catalogs.canonical(call.arguments()))) return true;
            }
        }
        return false;
    }

    private Binding lockedBinding(Scope scope) {
        Map<String, Object> run = lockRun(scope.runId());
        if (!"RUNNING".equals(run.get("status")) || run.get("cancel_requested_at") != null
                || !"langchain-ga.v1".equals(run.get("engine_version"))) throw new IllegalStateException("GA_EXECUTION_FENCE");
        var rows = jdbc.query("""
                SELECT * FROM ga_executions WHERE run_id=:run AND execution_epoch=:epoch AND lease_id=:lease
                AND deadline>clock_timestamp() FOR UPDATE
                """, params(scope), (rs, i) -> new Binding(rs.getObject("thread_id", UUID.class),
                rs.getObject("model_binding_id", UUID.class), rs.getString("provider"), rs.getString("model"),
                rs.getString("settings_revision"), rs.getTimestamp("deadline").toInstant(), readTools(rs.getString("allowed_tools"))));
        if (rows.size() != 1) throw new IllegalStateException("GA_EXECUTION_FENCE");
        return rows.getFirst();
    }
    private Map<String, Object> lockRun(UUID run) {
        var rows = jdbc.queryForList("SELECT * FROM global_assistant_runs WHERE id=:run FOR UPDATE", Maps.of("run", run));
        if (rows.size() != 1) throw new IllegalStateException("GA_EXECUTION_FENCE");
        return rows.getFirst();
    }
    private Map<String, Object> params(Scope scope) {
        return Maps.of("run", scope.runId(), "epoch", scope.epoch(), "lease", scope.leaseId());
    }
    private String toolsJson(java.util.List<GaModelContract.Tool> tools) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tools);
            if (json.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException();
            return json;
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid GA catalog snapshot"); }
    }
    private java.util.List<GaModelContract.Tool> readTools(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<GaModelContract.Tool>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid GA catalog snapshot"); }
    }
}
