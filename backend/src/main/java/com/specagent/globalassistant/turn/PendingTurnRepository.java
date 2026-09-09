package com.specagent.globalassistant.turn;

import com.specagent.common.Ids;
import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence owner for durable steers. DB partial unique index is the
 * final arbiter of max-one-unresolved-per-thread.
 */
@Repository
public class PendingTurnRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<PendingTurn> rowMapper;

    public PendingTurnRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> new PendingTurn(
                rs.getObject("id", UUID.class),
                rs.getObject("thread_id", UUID.class),
                rs.getObject("interrupted_run_id", UUID.class),
                rs.getString("message"),
                rs.getString("ui_context"),
                PendingTurnStatus.fromCode(rs.getString("status")),
                rs.getObject("successor_run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("discarded_at") == null ? null : rs.getTimestamp("discarded_at").toInstant());
    }

    public PendingTurn insert(UUID threadId, UUID interruptedRunId, String message, String uiContextJson) {
        UUID id = Ids.random();
        try {
            jdbc.update(
                    "INSERT INTO global_assistant_pending_turns (id, thread_id, interrupted_run_id, message, ui_context, status) VALUES (:id, :threadId, :runId, :message, CAST(:ui AS jsonb), 'PENDING')",
                    Maps.of("id", id, "threadId", threadId, "runId", interruptedRunId, "message", message, "ui", uiContextJson == null ? "{}" : uiContextJson));
        } catch (DataIntegrityViolationException ex) {
            throw new SteerPendingException("Thread already hosts an unresolved steer", ex);
        }
        return findById(id).orElseThrow();
    }

    public Optional<PendingTurn> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.getJdbcOperations().queryForObject(
                    "SELECT * FROM global_assistant_pending_turns WHERE id = ?", rowMapper, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<PendingTurn> findUnresolvedByThread(UUID threadId) {
        List<PendingTurn> rows = jdbc.query(
                "SELECT * FROM global_assistant_pending_turns WHERE thread_id = :threadId AND status IN ('PENDING','CLAIMED') ORDER BY created_at DESC LIMIT 1",
                Maps.of("threadId", threadId), rowMapper);
        return rows.stream().findFirst();
    }

    public PendingTurn lockById(UUID id) {
        List<PendingTurn> rows = jdbc.query(
                "SELECT * FROM global_assistant_pending_turns WHERE id = :id FOR UPDATE",
                Maps.of("id", id), rowMapper);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Pending turn not found: " + id);
        }
        return rows.get(0);
    }

    public PendingTurn lockUnresolvedByThread(UUID threadId) {
        List<PendingTurn> rows = jdbc.query(
                "SELECT * FROM global_assistant_pending_turns WHERE thread_id = :threadId AND status IN ('PENDING','CLAIMED') FOR UPDATE",
                Maps.of("threadId", threadId), rowMapper);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No unresolved pending turn for thread: " + threadId);
        }
        return rows.get(0);
    }

    public void markClaimed(UUID id) {
        jdbc.update("UPDATE global_assistant_pending_turns SET status = 'CLAIMED', claimed_at = :now WHERE id = :id AND status = 'PENDING'", Maps.of("id", id, "now", Timestamp.from(Instant.now())));
    }

    public void markConsumed(UUID id, UUID successorRunId) {
        jdbc.update("UPDATE global_assistant_pending_turns SET status = 'CONSUMED', successor_run_id = :runId, consumed_at = :now WHERE id = :id AND status IN ('PENDING','CLAIMED')", Maps.of("id", id, "runId", successorRunId, "now", Timestamp.from(Instant.now())));
    }

    public int markDiscardedByThread(UUID threadId) {
        return jdbc.update("UPDATE global_assistant_pending_turns SET status = 'DISCARDED', discarded_at = :now WHERE thread_id = :threadId AND status IN ('PENDING','CLAIMED')", Maps.of("threadId", threadId, "now", Timestamp.from(Instant.now())));
    }

    public int deleteByThread(UUID threadId) {
        return jdbc.update("DELETE FROM global_assistant_pending_turns WHERE thread_id = :threadId", Maps.of("threadId", threadId));
    }

    public List<PendingTurn> findStrandedPending() {
        return jdbc.query("SELECT * FROM global_assistant_pending_turns WHERE status IN ('PENDING','CLAIMED') ORDER BY created_at", rowMapper);
    }
}
