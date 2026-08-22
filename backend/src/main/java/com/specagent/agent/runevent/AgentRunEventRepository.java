package com.specagent.agent.runevent;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only persistence for {@code agent_run_events}. Events are never
 * updated or deleted; the per-run sequence is assigned atomically at insert
 * time so concurrent writers cannot interleave numbering.
 */
@Repository
public class AgentRunEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<AgentRunEvent> rowMapper;

    public AgentRunEventRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new AgentRunEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("sequence"),
                AgentRunPhase.fromCode(rs.getString("phase")),
                rs.getString("event_type"),
                json.read(rs.getString("payload"), Map.class),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Appends one event with the next per-run sequence number in a single
     * atomic statement.
     */
    public void append(AgentRunEvent event) {
        String sql = """
                INSERT INTO agent_run_events (id, run_id, sequence, phase, event_type, payload, created_at)
                SELECT :id, :runId, COALESCE(MAX(sequence), 0) + 1, :phase, :eventType,
                       CAST(:payload AS jsonb), :createdAt
                FROM agent_run_events WHERE run_id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", event.id(),
                "runId", event.runId(),
                "phase", event.phase().code(),
                "eventType", event.eventType(),
                "payload", json.write(event.payload() == null ? Map.of() : event.payload()),
                "createdAt", Timestamp.from(event.createdAt())));
    }

    public List<AgentRunEvent> findByRunId(UUID runId) {
        String sql = """
                SELECT * FROM agent_run_events WHERE run_id = :runId ORDER BY sequence
                """;
        return jdbcTemplate.query(sql, Maps.of("runId", runId), rowMapper);
    }
}
