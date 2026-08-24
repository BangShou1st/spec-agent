package com.specagent.graph;

import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class GraphOperationRepository {

    private static final TypeReference<List<UUID>> TARGET_LIST = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> REF_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<GraphOperation> rowMapper;

    public GraphOperationRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new GraphOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                GraphOperation.Actor.valueOf(rs.getString("actor")),
                GraphOperation.Type.valueOf(rs.getString("type")),
                json.readList(rs.getString("targets"), TARGET_LIST),
                json.read(rs.getString("before_refs"), REF_MAP),
                json.read(rs.getString("after_refs"), REF_MAP),
                rs.getString("caused_by"),
                rs.getBoolean("reversible"),
                GraphOperation.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("undone_at") == null ? null : rs.getTimestamp("undone_at").toInstant());
    }

    public void save(GraphOperation operation) {
        String sql = """
                INSERT INTO graph_operations (id, project_id, actor, type, targets,
                                              before_refs, after_refs, caused_by, reversible,
                                              status, created_at, undone_at)
                VALUES (:id, :projectId, :actor, :type, CAST(:targets AS jsonb),
                        CAST(:beforeRefs AS jsonb), CAST(:afterRefs AS jsonb), :causedBy, :reversible,
                        :status, :createdAt, :undoneAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", operation.id(),
                "projectId", operation.projectId(),
                "actor", operation.actor().name(),
                "type", operation.type().name(),
                "targets", json.write(operation.targets()),
                "beforeRefs", json.write(operation.beforeRefs()),
                "afterRefs", json.write(operation.afterRefs()),
                "causedBy", operation.causedBy(),
                "reversible", operation.reversible(),
                "status", operation.status().name(),
                "createdAt", Timestamp.from(operation.createdAt()),
                "undoneAt", operation.undoneAt() == null ? null : Timestamp.from(operation.undoneAt())));
    }

    public void updateStatus(UUID id, GraphOperation.Status status, Instant changedAt) {
        String sql = """
                UPDATE graph_operations
                SET status = :status, undone_at = :undoneAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", id,
                "status", status.name(),
                "undoneAt", status == GraphOperation.Status.UNDONE ? Timestamp.from(changedAt) : null));
    }

    public Optional<GraphOperation> findById(UUID id) {
        String sql = "SELECT * FROM graph_operations WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    /** Full log in creation order; undo/redo stack semantics are derived from it. */
    public List<GraphOperation> findByProject(UUID projectId) {
        String sql = """
                SELECT * FROM graph_operations
                WHERE project_id = :projectId
                ORDER BY created_at, id
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    public GraphOperation append(UUID projectId,
                                 GraphOperation.Actor actor,
                                 GraphOperation.Type type,
                                 List<UUID> targets,
                                 Map<String, Object> beforeRefs,
                                 Map<String, Object> afterRefs) {
        return append(projectId, actor, type, targets, beforeRefs, afterRefs, null);
    }

    public GraphOperation append(UUID projectId,
                                 GraphOperation.Actor actor,
                                 GraphOperation.Type type,
                                 List<UUID> targets,
                                 Map<String, Object> beforeRefs,
                                 Map<String, Object> afterRefs,
                                 String causedBy) {
        GraphOperation operation = new GraphOperation(
                Ids.random(), projectId, actor, type, targets, beforeRefs, afterRefs,
                causedBy, type.reversibleByDefault(), GraphOperation.Status.ACTIVE,
                nextTimestamp(), null);
        save(operation);
        return operation;
    }

    /**
     * Strictly increasing wall-clock timestamps for operation lifecycle
     * stamps. Undo/redo cutoff compares createdAt against undoneAt with a
     * strict "after", so two operations stamped with the same Instant would
     * make new work invisible to the cutoff check; this tick guarantees a
     * total order within one node.
     */
    public static Instant nextTimestamp() {
        while (true) {
            Instant candidate = Instant.now();
            Instant last = LAST_TICK.get();
            if (!candidate.isAfter(last)) {
                candidate = last.plusNanos(1_000);
            }
            if (LAST_TICK.compareAndSet(last, candidate)) {
                return candidate;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicReference<Instant> LAST_TICK =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
}
