package com.specagent.agent;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<AgentRun> rowMapper;

    public AgentRunRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new AgentRun(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                AgentRunTriggerType.fromCode(rs.getString("trigger_type")),
                rs.getObject("input_node_id", UUID.class),
                rs.getObject("context_snapshot_id", UUID.class),
                rs.getObject("produced_node_id", UUID.class),
                rs.getObject("produced_answer_id", UUID.class),
                rs.getObject("produced_patch_id", UUID.class),
                rs.getObject("produced_spec_snapshot_id", UUID.class),
                AgentRunStatus.fromCode(rs.getString("status")),
                rs.getString("trace"),
                rs.getString("operation"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    public void save(AgentRun run) {
        String sql = """
                INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id,
                                        context_snapshot_id, produced_node_id, produced_answer_id,
                                        produced_patch_id, produced_spec_snapshot_id, status, trace,
                                        operation, created_at, completed_at)
                VALUES (:id, :projectId, :routeId, :triggerType, :inputNodeId, :contextSnapshotId,
                        :producedNodeId, :producedAnswerId, :producedPatchId, :producedSpecSnapshotId,
                        :status, CAST(:trace AS jsonb), :operation, :createdAt, :completedAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", run.id(),
                "projectId", run.projectId(),
                "routeId", run.routeId(),
                "triggerType", run.triggerType().code(),
                "inputNodeId", run.inputNodeId(),
                "contextSnapshotId", run.contextSnapshotId(),
                "producedNodeId", run.producedNodeId(),
                "producedAnswerId", run.producedAnswerId(),
                "producedPatchId", run.producedPatchId(),
                "producedSpecSnapshotId", run.producedSpecSnapshotId(),
                "status", run.status().code(),
                "trace", json.write(run.trace()),
                "operation", run.operation(),
                "createdAt", Timestamp.from(run.createdAt()),
                "completedAt", run.completedAt() == null ? null : Timestamp.from(run.completedAt())));
    }

    public void updateStatus(UUID runId, AgentRunStatus status, Instant completedAt, String trace) {
        String sql = """
                UPDATE agent_runs SET status = :status, completed_at = :completedAt, trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", status.code(),
                "completedAt", completedAt == null ? null : Timestamp.from(completedAt),
                "trace", json.write(trace)));
    }

    /**
     * Records that the run's context snapshot was built and frozen.
     */
    public void attachContext(UUID runId, UUID contextSnapshotId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET context_snapshot_id = :contextSnapshotId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "contextSnapshotId", contextSnapshotId,
                "status", AgentRunStatus.CONTEXT_BUILT.code(),
                "trace", json.write(trace)));
    }

    /**
     * Records that the model adapter was called for this run.
     */
    public void markModelCalled(UUID runId, String trace) {
        updateStatusWithTrace(runId, AgentRunStatus.MODEL_CALLED, trace);
    }

    /**
     * Records that reflection gates ran over the model's proposal.
     */
    public void markReflected(UUID runId, String trace) {
        updateStatusWithTrace(runId, AgentRunStatus.REFLECTED, trace);
    }

    /**
     * Records the node persisted by this run.
     */
    public void markPersistedNode(UUID runId, UUID producedNodeId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET produced_node_id = :producedNodeId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedNodeId", producedNodeId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace)));
    }

    /**
     * Records the answer persisted by this run.
     */
    public void markPersistedAnswer(UUID runId, UUID producedAnswerId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET produced_answer_id = :producedAnswerId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedAnswerId", producedAnswerId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace)));
    }

    /**
     * Records the answer patch persisted by this run.
     */
    public void markPersistedAnswerPatch(UUID runId, UUID producedPatchId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET produced_patch_id = :producedPatchId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedPatchId", producedPatchId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace)));
    }

    /**
     * Records the spec snapshot persisted by this run.
     */
    public void markPersistedSpecSnapshot(UUID runId, UUID producedSpecSnapshotId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET produced_spec_snapshot_id = :producedSpecSnapshotId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedSpecSnapshotId", producedSpecSnapshotId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace)));
    }

    /**
     * Marks a run failed. Failure is terminal, so the run is closed.
     */
    public void fail(UUID runId, String trace) {
        String sql = """
                UPDATE agent_runs
                SET status = :status,
                    completed_at = :completedAt,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", AgentRunStatus.FAILED.code(),
                "completedAt", Timestamp.from(Instant.now()),
                "trace", json.write(trace)));
    }

    private void updateStatusWithTrace(UUID runId, AgentRunStatus status, String trace) {
        String sql = """
                UPDATE agent_runs
                SET status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", status.code(),
                "trace", json.write(trace)));
    }

    public Optional<AgentRun> findById(UUID id) {
        String sql = "SELECT * FROM agent_runs WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<AgentRun> findByProject(UUID projectId) {
        String sql = "SELECT * FROM agent_runs WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    /**
     * Atomically claims the oldest queued decision-cycle run by moving it
     * from CREATED to RUNNING. Returns empty when no run is claimable. The
     * single-statement claim keeps concurrent workers from double-executing.
     */
    public Optional<AgentRun> claimNextDecisionCycleRun() {
        String sql = """
                UPDATE agent_runs SET status = :running
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.DECISION_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code()),
                rowMapper).stream().findFirst();
    }

    /**
     * Atomically claims the oldest queued answer-cycle run by moving it
     * from CREATED to RUNNING.
     */
    public Optional<AgentRun> claimNextAnswerCycleRun() {
        String sql = """
                UPDATE agent_runs SET status = :running
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.ANSWER_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code()),
                rowMapper).stream().findFirst();
    }

    /**
     * Atomically claims one specific queued decision-cycle run by id. The
     * claim stays conditional on the CREATED status, so a run already claimed
     * (or executed) by anyone else is never claimed twice.
     */
    public Optional<AgentRun> claimDecisionCycleRun(UUID runId) {
        String sql = """
                UPDATE agent_runs SET status = :running
                WHERE id = :id AND trigger_type = :trigger AND status = :created
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "id", runId,
                        "trigger", AgentRunTriggerType.DECISION_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code()),
                rowMapper).stream().findFirst();
    }

    /**
     * Atomically claims the oldest queued node-query run by moving it
     * from CREATED to RUNNING.
     */
    public Optional<AgentRun> claimNextNodeQueryRun() {
        String sql = """
                UPDATE agent_runs SET status = :running
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.NODE_QUERY.code(),
                        "created", AgentRunStatus.CREATED.code()),
                rowMapper).stream().findFirst();
    }
}
