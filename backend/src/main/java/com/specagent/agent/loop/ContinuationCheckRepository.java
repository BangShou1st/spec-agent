package com.specagent.agent.loop;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Durable continuation outbox over {@code agent_run_continuation_checks}.
 *
 * <p>One row per terminal run: {@code requested_at} when terminalization
 * committed, {@code processed_at} when the dispatcher evaluated it. No
 * semantic fields — the coordinator re-reads durable run facts, never this
 * table, to decide. A crashed afterCommit leaves a pending row for the
 * recovery scanner; a re-request after PARKED_APPROVAL reopens via upsert.
 */
@Repository
public class ContinuationCheckRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ContinuationCheckRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Requests continuation evaluation for a terminal run. Reopening is
     * explicit: a processed row (e.g. PARKED_APPROVAL later accepted) is
     * reset to pending so recovery/dispatch evaluates fresh durable facts.
     */
    public void request(UUID runId) {
        String sql = """
                INSERT INTO agent_run_continuation_checks (run_id, requested_at, processed_at)
                VALUES (:runId, CURRENT_TIMESTAMP, NULL)
                ON CONFLICT (run_id) DO UPDATE
                    SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL
                """;
        jdbcTemplate.update(sql, Maps.of("runId", runId));
    }

    /** Oldest pending checks first, bounded so one poll never scans the table. */
    public List<UUID> findPending(int limit) {
        String sql = """
                SELECT run_id FROM agent_run_continuation_checks
                WHERE processed_at IS NULL
                ORDER BY requested_at
                LIMIT :limit
                """;
        return jdbcTemplate.queryForList(sql, Maps.of("limit", limit), UUID.class);
    }

    public void markProcessed(UUID runId) {
        String sql = """
                UPDATE agent_run_continuation_checks
                SET processed_at = CURRENT_TIMESTAMP
                WHERE run_id = :runId AND processed_at IS NULL
                """;
        jdbcTemplate.update(sql, Maps.of("runId", runId));
    }
}
