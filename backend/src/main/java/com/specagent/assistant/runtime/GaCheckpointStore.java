package com.specagent.assistant.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thread-scoped saver RPC backed by the host DB. No decoding of arbitrary Python objects. */
@Service
public class GaCheckpointStore {
    private static final Set<String> BASE = Set.of("protocolVersion", "runId", "executionEpoch", "leaseId",
            "threadId", "namespace", "expectedVersion");
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "GET", Set.of("checkpointId"), "LIST", Set.of("before", "limit", "filter"),
            "PUT", Set.of("checkpointId", "parentCheckpointId", "checkpoint", "metadata", "newVersions"),
            "PUT_WRITES", Set.of("checkpointId", "taskId", "taskPath", "writes"), "DELETE_THREAD", Set.of());
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final NamedParameterJdbcTemplate jdbc;
    private final GaExecutionStore executions;
    public GaCheckpointStore(NamedParameterJdbcTemplate jdbc, GaExecutionStore executions) {
        this.jdbc = jdbc; this.executions = executions;
    }

    @Transactional
    public Map<String, Object> apply(String operation, String json) {
        JsonNode request = read(json, 4194304);
        require(FIELDS.containsKey(operation));
        Set<String> expected = new HashSet<>(BASE); expected.addAll(FIELDS.get(operation));
        fields(request, expected);
        require("ga-checkpoint.v1".equals(text(request, "protocolVersion", 40))
                && "ga:langchain-ga.v1".equals(text(request, "namespace", 40)));
        long epoch = integer(request.get("executionEpoch"));
        long expectedVersion = integer(request.get("expectedVersion"));
        require(epoch > 0 && expectedVersion >= 0);
        var scope = new GaExecutionStore.Scope(uuid(request, "runId"), epoch, uuid(request, "leaseId"));
        var binding = executions.authorize(scope); // Run lock precedes head lock in every operation.
        UUID thread = uuid(request, "threadId");
        require(thread.equals(binding.threadId()));
        Map<String, Object> params = Maps.of("thread", thread);
        long version = jdbc.queryForObject("SELECT version FROM ga_checkpoint_heads WHERE thread_id=:thread FOR UPDATE", params, Long.class);
        require(expectedVersion <= version);
        Object result = null;
        boolean changed = false;
        switch (operation) {
            case "GET" -> {
                String id = nullableText(request, "checkpointId", 128);
                params.put("id", id);
                var rows = jdbc.queryForList("SELECT * FROM ga_checkpoints WHERE thread_id=:thread "
                        + (id == null ? "ORDER BY checkpoint_id DESC LIMIT 1" : "AND checkpoint_id=:id"), params);
                result = rows.isEmpty() ? null : tuple(rows.getFirst());
            }
            case "LIST" -> {
                long limit = integer(request.get("limit")); require(limit >= 1 && limit <= 100);
                String before = nullableText(request, "before", 128);
                JsonNode filter = request.get("filter"); require(filter.isObject() && filter.size() <= 32);
                params.putAll(Maps.of("before", before, "limit", (int) limit));
                // Bound DB work as well as returned records. Metadata filtering uses tagged JSON values.
                StringBuilder query = new StringBuilder("SELECT * FROM ga_checkpoints WHERE thread_id=:thread");
                if (before != null) query.append(" AND checkpoint_id<:before");
                int index = 0;
                for (var entry : filter.properties()) {
                    require(entry.getKey().length() <= 128);
                    require(entry.getValue().isValueNode());
                    params.put("key" + index, entry.getKey()); params.put("value" + index, entry.getValue().toString());
                    query.append(" AND (CAST(CAST(metadata AS jsonb)->>'payload' AS jsonb)->'value'->:key")
                            .append(index).append(")=CAST(:value").append(index).append(" AS jsonb)");
                    index++;
                }
                query.append(" ORDER BY checkpoint_id DESC LIMIT :limit");
                List<Object> tuples = new ArrayList<>();
                int bytes = 0;
                for (var row : jdbc.queryForList(query.toString(), params)) {
                    var value = tuple(row);
                    int size = json(value).getBytes(StandardCharsets.UTF_8).length;
                    if (bytes + size > 4194304) break;
                    tuples.add(value); bytes += size;
                }
                result = tuples;
            }
            case "PUT" -> {
                String id = text(request, "checkpointId", 128);
                String parent = nullableText(request, "parentCheckpointId", 128);
                String checkpoint = envelope(request.get("checkpoint"));
                String metadata = envelope(request.get("metadata"));
                String newVersions = envelope(request.get("newVersions"));
                var payload = read(request.get("checkpoint").get("payload").textValue(), 1048576);
                require(payload.path("tag").asText().equals("dict") && id.equals(payload.path("value").path("id").asText()));
                params.putAll(Maps.of("id", id, "parent", parent, "checkpoint", checkpoint, "metadata", metadata, "versions", newVersions));
                var old = jdbc.queryForList("SELECT * FROM ga_checkpoints WHERE thread_id=:thread AND checkpoint_id=:id", params);
                if (!old.isEmpty()) {
                    var row = old.getFirst();
                    require(Objects.equals(parent, row.get("parent_checkpoint_id")) && checkpoint.equals(row.get("checkpoint"))
                            && metadata.equals(row.get("metadata")) && newVersions.equals(row.get("new_versions")));
                } else {
                    cas(expectedVersion, version);
                    if (parent != null) {
                        require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ga_checkpoints WHERE thread_id=:thread AND checkpoint_id=:parent)", params, Boolean.class)));
                    }
                    jdbc.update("INSERT INTO ga_checkpoints(thread_id,checkpoint_id,parent_checkpoint_id,checkpoint,metadata,new_versions) VALUES(:thread,:id,:parent,:checkpoint,:metadata,:versions)", params);
                    changed = true;
                }
            }
            case "PUT_WRITES" -> {
                String id = text(request, "checkpointId", 128), task = text(request, "taskId", 128);
                String path = textAllowEmpty(request, "taskPath", 1024);
                JsonNode writes = request.get("writes"); require(writes.isArray() && writes.size() <= 128);
                params.putAll(Maps.of("id", id, "task", task, "path", path));
                require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ga_checkpoints WHERE thread_id=:thread AND checkpoint_id=:id)", params, Boolean.class)));
                Set<Long> seen = new HashSet<>();
                for (JsonNode write : writes) {
                    fields(write, Set.of("index", "channel", "value"));
                    long number = integer(write.get("index")); require(number >= -4 && number <= 127 && seen.add(number));
                    String channel = text(write, "channel", 128), value = envelope(write.get("value"));
                    params.putAll(Maps.of("index", (int) number, "channel", channel, "value", value));
                    var old = jdbc.queryForList("SELECT * FROM ga_checkpoint_writes WHERE thread_id=:thread AND checkpoint_id=:id AND task_id=:task AND write_index=:index", params);
                    if (!old.isEmpty() && channel.equals(old.getFirst().get("channel"))
                            && value.equals(old.getFirst().get("value")) && path.equals(old.getFirst().get("task_path"))) continue;
                    cas(expectedVersion, version);
                    // LangGraph reserved negative channels are upserts; ordinary writes are first-write-only.
                    require(old.isEmpty() || number < 0);
                    jdbc.update("""
                            INSERT INTO ga_checkpoint_writes(thread_id,checkpoint_id,task_id,task_path,write_index,channel,value)
                            VALUES(:thread,:id,:task,:path,:index,:channel,:value)
                            ON CONFLICT(thread_id,checkpoint_id,task_id,write_index) DO UPDATE
                            SET task_path=EXCLUDED.task_path,channel=EXCLUDED.channel,value=EXCLUDED.value
                            """, params);
                    changed = true;
                }
                // Validate the combined pending envelope before this batch can commit.
                tuple(jdbc.queryForMap("SELECT * FROM ga_checkpoints WHERE thread_id=:thread AND checkpoint_id=:id", params));
            }
            case "DELETE_THREAD" -> {
                cas(expectedVersion, version);
                // Retain the head/version to prevent ABA after delete and subsequent writes.
                changed = jdbc.update("DELETE FROM ga_checkpoints WHERE thread_id=:thread", params) > 0;
                jdbc.update("UPDATE ga_checkpoint_heads SET completed_checkpoint_id=NULL,public_history_boundary=NULL WHERE thread_id=:thread",params);
            }
            default -> throw new IllegalArgumentException("Unknown GA checkpoint operation");
        }
        if (changed) {
            jdbc.update("UPDATE ga_checkpoint_heads SET version=version+1 WHERE thread_id=:thread", params);
            version++;
        }
        return Maps.of("protocolVersion", "ga-checkpoint.v1", "version", version, "result", result);
    }

    private Map<String, Object> tuple(Map<String, Object> row) {
        var params = Maps.of("thread", row.get("thread_id"), "id", row.get("checkpoint_id"));
        List<JsonNode> writes = new ArrayList<>();
        for (var write : jdbc.queryForList("SELECT * FROM ga_checkpoint_writes WHERE thread_id=:thread AND checkpoint_id=:id ORDER BY task_id,write_index", params)) {
            // Tagged list of tuples matches SafeCheckpointCodec, keeping values as encoded trees.
            writes.add(mapper.valueToTree(Map.of("tag", "tuple", "value", List.of(
                    write.get("task_id"), write.get("channel"),
                    read(read((String) write.get("value"), 1100000).get("payload").textValue(), 1048576)))));
        }
        String payload = json(Map.of("tag", "list", "value", writes));
        require(payload.getBytes(StandardCharsets.UTF_8).length <= 1048576);
        return Maps.of("checkpoint", read((String) row.get("checkpoint"), 1100000),
                "metadata", read((String) row.get("metadata"), 1100000), "parentCheckpointId", row.get("parent_checkpoint_id"),
                "pendingWrites", Maps.of("codecVersion", "ga-json.v1", "frameworkVersion", "langgraph-1.2.12/checkpoint-4.2.0",
                        "stateVersion", "langchain-ga.v1", "payload", payload, "payloadHash", Hashes.sha256Hex(payload)));
    }
    private String envelope(JsonNode node) {
        fields(node, Set.of("codecVersion", "frameworkVersion", "stateVersion", "payload", "payloadHash"));
        require("ga-json.v1".equals(text(node, "codecVersion", 40))
                && "langgraph-1.2.12/checkpoint-4.2.0".equals(text(node, "frameworkVersion", 80))
                && "langchain-ga.v1".equals(text(node, "stateVersion", 40)));
        String payload = textAllowEmpty(node, "payload", 1048576);
        require(payload.getBytes(StandardCharsets.UTF_8).length <= 1048576
                && Hashes.sha256Hex(payload).equals(text(node, "payloadHash", 64)));
        read(payload, 1048576); // Valid JSON, no duplicate keys or trailing content.
        return json(node);
    }
    private JsonNode read(String value, int max) {
        try {
            require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= max);
            JsonNode node = mapper.readTree(value); require(node != null); return node;
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid GA checkpoint payload"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid GA checkpoint payload"); }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject());
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }
    private static String text(JsonNode node, String key, int max) {
        String value = textAllowEmpty(node, key, max); require(!value.isBlank()); return value;
    }
    private static String textAllowEmpty(JsonNode node, String key, int max) {
        JsonNode value = node.get(key); require(value != null && value.isTextual() && value.textValue().length() <= max); return value.textValue();
    }
    private static String nullableText(JsonNode node, String key, int max) {
        JsonNode value = node.get(key); require(value != null); return value.isNull() ? null : text(node, key, max);
    }
    private static long integer(JsonNode value) { require(value != null && value.isIntegralNumber() && value.canConvertToLong()); return value.longValue(); }
    private static UUID uuid(JsonNode node, String key) { return UUID.fromString(text(node, key, 36)); }
    private static void cas(long expected, long actual) { if (expected != actual) throw new IllegalStateException("GA_CHECKPOINT_CAS_CONFLICT"); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid GA checkpoint payload"); }
}
