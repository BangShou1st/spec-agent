package com.specagent.context;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class ContextSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<ContextSnapshot> rowMapper;

    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<ContextRelation>> RELATION_LIST =
            new TypeReference<>() {
            };

    public ContextSnapshotRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new ContextSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("tip_node_id", UUID.class),
                ContextOperationType.fromCode(rs.getString("operation_type")),
                json.readList(rs.getString("included_node_ids"), UUID_LIST),
                json.readList(rs.getString("included_answer_ids"), UUID_LIST),
                json.readList(rs.getString("included_patch_ids"), UUID_LIST),
                json.readList(rs.getString("excluded_route_ids"), UUID_LIST),
                json.readList(rs.getString("related_node_ids"), UUID_LIST),
                json.readList(rs.getString("relations_json"), RELATION_LIST),
                rs.getString("special_inputs"),
                rs.getString("context_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    public void save(ContextSnapshot snapshot) {
        String sql = """
                INSERT INTO context_snapshots (id, project_id, route_id, tip_node_id, operation_type,
                                               included_node_ids, included_answer_ids, included_patch_ids,
                                               excluded_route_ids, related_node_ids, relations_json,
                                               special_inputs, context_hash, created_at)
                VALUES (:id, :projectId, :routeId, :tipNodeId, :operationType,
                        CAST(:includedNodeIds AS jsonb), CAST(:includedAnswerIds AS jsonb),
                        CAST(:includedPatchIds AS jsonb), CAST(:excludedRouteIds AS jsonb),
                        CAST(:relatedNodeIds AS jsonb), CAST(:relationsJson AS jsonb),
                        CAST(:specialInputs AS jsonb), :contextHash, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", snapshot.id(),
                "projectId", snapshot.projectId(),
                "routeId", snapshot.routeId(),
                "tipNodeId", snapshot.tipNodeId(),
                "operationType", snapshot.operationType().code(),
                "includedNodeIds", json.writeList(snapshot.includedNodeIds()),
                "includedAnswerIds", json.writeList(snapshot.includedAnswerIds()),
                "includedPatchIds", json.writeList(snapshot.includedPatchIds()),
                "excludedRouteIds", json.writeList(snapshot.excludedRouteIds()),
                "relatedNodeIds", json.writeList(snapshot.relatedNodeIds()),
                "relationsJson", json.writeList(snapshot.relations()),
                // specialInputs is already a serialized JSON object text; it
                // must be stored as-is into the jsonb column, never
                // re-serialized (re-serializing would double-encode the JSON
                // string and break the read-back projection).
                "specialInputs", snapshot.specialInputs(),
                "contextHash", snapshot.contextHash(),
                "createdAt", Timestamp.from(snapshot.createdAt())));
    }

    public Optional<ContextSnapshot> findById(UUID id) {
        String sql = "SELECT * FROM context_snapshots WHERE id = :id";
        return jdbcTemplate.query(sql, Map.of("id", id), rowMapper).stream().findFirst();
    }

    public List<ContextSnapshot> findByRoute(UUID routeId) {
        String sql = "SELECT * FROM context_snapshots WHERE route_id = :routeId ORDER BY created_at";
        return jdbcTemplate.query(sql, Map.of("routeId", routeId), rowMapper);
    }
}
