package com.specagent.globalassistant.conversation;

import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Serialized per-run event append. Every append locks the run row FOR UPDATE
 * first, then computes next sequence. Runtime/cancel/terminal events share
 * this path. No Redis/Kafka/global sequence.
 */
@Repository
public class GlobalAssistantRunEventRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<GlobalAssistantRunEvent> rowMapper;
    public GlobalAssistantRunEventRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantRunEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("type"),
                json.read(rs.getString("payload"), Map.class),
                rs.getTimestamp("created_at").toInstant());
    }
    /**
     * Appends one event. Locks the run row FOR UPDATE in the same transaction
     * so concurrent writers serialize their sequence numbers.
     */
    @org.springframework.transaction.annotation.Transactional
    public GlobalAssistantRunEvent append(UUID runId, String type, Map<String, Object> payload) {
        List<UUID> locked = jdbc.queryForList(
                "SELECT id FROM global_assistant_runs WHERE id = :runId FOR UPDATE",
                Maps.of("runId", runId), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Global assistant run not found: " + runId);
        }
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM global_assistant_run_events WHERE run_id = :runId",
                Maps.of("runId", runId), Integer.class);
        int next = (max == null ? 0 : max) + 1;
        UUID id = Ids.random();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO global_assistant_run_events (id, run_id, sequence, type, payload, created_at) VALUES (:id, :runId, :seq, :type, CAST(:payload AS jsonb), :now)",
                Maps.of("id", id, "runId", runId, "seq", next, "type", type,
                        "payload", json.write(payload == null ? Map.of() : payload), "now", Timestamp.from(now)));
        Map<String, Object> stored = payload == null ? Map.of() : Map.copyOf(payload);
        return new GlobalAssistantRunEvent(id, runId, next, type, stored, now);
    }
    public List<GlobalAssistantRunEvent> findByRun(UUID runId) {
        return jdbc.query("SELECT * FROM global_assistant_run_events WHERE run_id = :runId ORDER BY sequence",
                Maps.of("runId", runId), rowMapper);
    }
    public List<GlobalAssistantRunEvent> findAfter(UUID runId, int cursor) {
        return jdbc.query(
                "SELECT * FROM global_assistant_run_events WHERE run_id = :runId AND sequence > :cursor ORDER BY sequence",
                Maps.of("runId", runId, "cursor", cursor), rowMapper);
    }
}
