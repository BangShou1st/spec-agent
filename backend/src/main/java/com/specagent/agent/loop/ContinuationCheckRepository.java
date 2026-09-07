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
 * committed, {@code processed_at} when the dispatcher evaluated the exact
 * {@code request_generation} it read. No semantic fields — the coordinator
 * re-reads durable run facts, never this table, to decide. A crashed
 * afterCommit leaves a pending row for the recovery scanner; a re-request
 * after PARKED_APPROVAL increments the generation and reopens evaluation.
 */
@Repository
public class ContinuationCheckRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ContinuationCheckRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Requests continuation evaluation for a terminal run. Every request
     * increments the generation and reopens evaluation: a processed row
     * (e.g. PARKED_APPROVAL later accepted) resets to pending under a NEW
     * generation, so a stale in-flight completion of the old generation can
     * never mark the new request processed (ABA-safe: it marks 0 rows).
     */
    public void request(UUID runId) {
        String sql = """
                INSERT INTO agent_run_continuation_checks
                    (run_id, requested_at, processed_at, request_generation)
                VALUES (:runId, CURRENT_TIMESTAMP, NULL, 1)
                ON CONFLICT (run_id) DO UPDATE
                    SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,
                        request_generation =
                            agent_run_continuation_checks.request_generation + 1
                """;
        jdbcTemplate.update(sql, Maps.of("runId", runId));
    }

    /** Oldest pending checks first, bounded so one poll never scans the table. */
    public List<ContinuationCheck> findPending(int limit) {
        String sql = """
                SELECT run_id, request_generation FROM agent_run_continuation_checks
                WHERE processed_at IS NULL
                ORDER BY requested_at
                LIMIT :limit
                """;
        return jdbcTemplate.query(sql, Maps.of("limit", limit),
                (rs, rowNum) -> new ContinuationCheck(
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("request_generation")));
    }

    /** The pending generation for one run, if any. */
    public java.util.Optional<ContinuationCheck> findPendingByRunId(UUID runId) {
        String sql = """
                SELECT run_id, request_generation FROM agent_run_continuation_checks
                WHERE run_id = :runId AND processed_at IS NULL
                """;
        return jdbcTemplate.query(sql, Maps.of("runId", runId),
                (rs, rowNum) -> new ContinuationCheck(
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("request_generation")))
                .stream().findFirst();
    }

    /** The latest generation requested for one run, pending or processed. */
    public long currentGeneration(UUID runId) {
        String sql = """
                SELECT request_generation FROM agent_run_continuation_checks
                WHERE run_id = :runId
                """;
        Long generation = jdbcTemplate.queryForObject(
                sql, Maps.of("runId", runId), Long.class);
        if (generation == null) {
            throw new IllegalStateException(
                    "No continuation check for run: " + runId);
        }
        return generation;
    }

    /**
     * Marks exactly the evaluated generation processed. Returns the marked
     * row count: 0 means a concurrent re-request already superseded this
     * generation — the new generation stays pending and recovery converges
     * it; the caller must not treat 0 as success of this generation.
     */
    public int markProcessed(UUID runId, long generation) {
        String sql = """
                UPDATE agent_run_continuation_checks
                SET processed_at = CURRENT_TIMESTAMP
                WHERE run_id = :runId
                  AND request_generation = :generation
                  AND processed_at IS NULL
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId, "generation", generation));
    }
}
