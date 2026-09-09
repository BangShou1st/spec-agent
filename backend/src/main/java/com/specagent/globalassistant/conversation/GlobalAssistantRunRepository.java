package com.specagent.globalassistant.conversation;

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
 * Run lifecycle persistence. The DB partial unique index is the final arbiter
 * of the single-active-run invariant; constraint conflicts map to 409.
 */
@Repository
public class GlobalAssistantRunRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<GlobalAssistantRun> rowMapper;
    public GlobalAssistantRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> new GlobalAssistantRun(
                rs.getObject("id", UUID.class),
                rs.getObject("thread_id", UUID.class),
                GlobalAssistantRunStatus.fromCode(rs.getString("status")),
                rs.getInt("step_count"),
                rs.getTimestamp("cancel_requested_at") == null ? null : rs.getTimestamp("cancel_requested_at").toInstant(),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("prompt_version"),
                rs.getString("context_projection_version"),
                rs.getString("tool_catalog_fingerprint"),
                rs.getString("error_code"));
    }
    public GlobalAssistantRun create(UUID threadId, String promptVersion, String contextProjectionVersion, String toolCatalogFingerprint) {
        UUID id = Ids.random();
        try {
            jdbc.update(
                    "INSERT INTO global_assistant_runs (id, thread_id, status, step_count, started_at, prompt_version, context_projection_version, tool_catalog_fingerprint) VALUES (:id, :threadId, 'CREATED', 0, :now, :prompt, :context, :fingerprint)",
                    Maps.of("id", id, "threadId", threadId, "now", Timestamp.from(Instant.now()),
                            "prompt", promptVersion, "context", contextProjectionVersion, "fingerprint", toolCatalogFingerprint));
        } catch (DataIntegrityViolationException ex) {
            throw new GlobalAssistantRunActiveException("Thread already hosts an active run", ex);
        }
        return findById(id).orElseThrow();
    }
    public Optional<GlobalAssistantRun> findById(UUID runId) {
        try {
            return Optional.ofNullable(jdbc.getJdbcOperations().queryForObject(
                    "SELECT * FROM global_assistant_runs WHERE id = ?", rowMapper, runId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
    public List<GlobalAssistantRun> findByThread(UUID threadId) {
        return jdbc.query("SELECT * FROM global_assistant_runs WHERE thread_id = :threadId ORDER BY started_at, id",
                Maps.of("threadId", threadId), rowMapper);
    }
    public List<GlobalAssistantRun> findActiveRuns() {
        return jdbc.query(
                "SELECT * FROM global_assistant_runs WHERE status IN ('CREATED','RUNNING') ORDER BY started_at, id",
                rowMapper);
    }
    public Optional<GlobalAssistantRun> findActiveByThread(UUID threadId) {
        List<GlobalAssistantRun> rows = jdbc.query(
                "SELECT * FROM global_assistant_runs WHERE thread_id = :threadId AND status IN ('CREATED','RUNNING') ORDER BY started_at DESC LIMIT 1",
                Maps.of("threadId", threadId), rowMapper);
        return rows.stream().findFirst();
    }
    /** Locks the run row FOR UPDATE inside the caller's transaction. */
    public GlobalAssistantRun lockById(UUID runId) {
        List<GlobalAssistantRun> rows = jdbc.query(
                "SELECT * FROM global_assistant_runs WHERE id = :runId FOR UPDATE",
                Maps.of("runId", runId), rowMapper);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Global assistant run not found: " + runId);
        }
        return rows.get(0);
    }
    public void markRunning(UUID runId) {
        int updated = jdbc.update(
                "UPDATE global_assistant_runs SET status = 'RUNNING' WHERE id = :runId AND status = 'CREATED'",
                Maps.of("runId", runId));
        if (updated != 1) {
            throw new IllegalStateException("Run is not in CREATED state: " + runId);
        }
    }
    public void incrementStep(UUID runId) {
        jdbc.update("UPDATE global_assistant_runs SET step_count = step_count + 1 WHERE id = :runId",
                Maps.of("runId", runId));
    }
    /**
     * Cooperative cancel signal: sets cancel_requested_at for CREATED/RUNNING only.
     * Never flips status directly. Idempotent.
     */
    public boolean requestCancel(UUID runId) {
        int updated = jdbc.update(
                "UPDATE global_assistant_runs SET cancel_requested_at = COALESCE(cancel_requested_at, :now) WHERE id = :runId AND status IN ('CREATED','RUNNING')",
                Maps.of("runId", runId, "now", Timestamp.from(Instant.now())));
        return updated == 1;
    }
    /**
     * Terminal transition. Terminal runs are immutable: no resurrection.
     */
    public void terminalize(UUID runId, GlobalAssistantRunStatus terminal, String errorCode) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal status: " + terminal);
        }
        int updated = jdbc.update(
                "UPDATE global_assistant_runs SET status = :status, completed_at = :now, error_code = :error WHERE id = :runId AND status IN ('CREATED','RUNNING')",
                Maps.of("runId", runId, "status", terminal.name(), "now", Timestamp.from(Instant.now()), "error", errorCode));
        if (updated != 1) {
            throw new IllegalStateException("Run is already terminal or missing: " + runId);
        }
    }
}
