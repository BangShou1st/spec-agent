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
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    public void save(AgentRun run) {
        String sql = """
                INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id,
                                        context_snapshot_id, produced_node_id, produced_answer_id,
                                        produced_patch_id, produced_spec_snapshot_id, status, trace,
                                        created_at, completed_at)
                VALUES (:id, :projectId, :routeId, :triggerType, :inputNodeId, :contextSnapshotId,
                        :producedNodeId, :producedAnswerId, :producedPatchId, :producedSpecSnapshotId,
                        :status, CAST(:trace AS jsonb), :createdAt, :completedAt)
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

    public Optional<AgentRun> findById(UUID id) {
        String sql = "SELECT * FROM agent_runs WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<AgentRun> findByProject(UUID projectId) {
        String sql = "SELECT * FROM agent_runs WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }
}
