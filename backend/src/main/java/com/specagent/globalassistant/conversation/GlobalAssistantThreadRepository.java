package com.specagent.globalassistant.conversation;

import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable threads with versioned working state + summary (optimistic CAS).
 */
@Repository
public class GlobalAssistantThreadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<GlobalAssistantThread> rowMapper;
    public GlobalAssistantThreadRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantThread(
                rs.getObject("id", UUID.class),
                rs.getString("summary"),
                rs.getInt("summary_version"),
                rs.getString("working_state"),
                rs.getInt("working_state_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
    public GlobalAssistantThread create() {
        UUID id = Ids.random();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO global_assistant_threads (id, summary, summary_version, working_state, working_state_version, created_at, updated_at) VALUES (:id, NULL, 0, NULL, 0, :now, :now)",
                Maps.of("id", id, "now", Timestamp.from(now)));
        return findById(id).orElseThrow();
    }
    public Optional<GlobalAssistantThread> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.getJdbcOperations().queryForObject(
                    "SELECT * FROM global_assistant_threads WHERE id = ?", rowMapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
    /**
     * Versioned working-state update. Fails closed on version conflict.
     */
    public void updateWorkingState(UUID threadId, Map<String, Object> workingState, int expectedVersion) {
        String sql = "UPDATE global_assistant_threads SET working_state = CAST(:state AS jsonb), working_state_version = working_state_version + 1, updated_at = :now WHERE id = :id AND working_state_version = :expected";
        int updated = jdbc.update(sql, Maps.of(
                "id", threadId,
                "state", json.write(workingState == null ? Map.of() : workingState),
                "now", Timestamp.from(Instant.now()),
                "expected", expectedVersion));
        if (updated != 1) {
            throw new GlobalAssistantVersionConflictException("WorkingState version conflict for thread: " + threadId);
        }
    }
    /**
     * Versioned summary update. Fails closed on version conflict.
     */
    public void updateSummary(UUID threadId, String summary, int expectedVersion) {
        String sql = "UPDATE global_assistant_threads SET summary = :summary, summary_version = summary_version + 1, updated_at = :now WHERE id = :id AND summary_version = :expected";
        int updated = jdbc.update(sql, Maps.of(
                "id", threadId,
                "summary", summary,
                "now", Timestamp.from(Instant.now()),
                "expected", expectedVersion));
        if (updated != 1) {
            throw new GlobalAssistantVersionConflictException("Summary version conflict for thread: " + threadId);
        }
    }
}
