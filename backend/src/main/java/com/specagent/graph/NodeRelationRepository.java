package com.specagent.graph;

import com.specagent.common.Ids;
import com.specagent.common.Maps;
import org.springframework.dao.DuplicateKeyException;
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
public class NodeRelationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<NodeRelation> rowMapper;

    public NodeRelationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> new NodeRelation(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("source_node_id", UUID.class),
                rs.getObject("target_node_id", UUID.class),
                NodeRelationType.fromCode(rs.getString("relation_type")),
                NodeRelation.Origin.valueOf(rs.getString("origin")),
                NodeRelation.Status.valueOf(rs.getString("status")),
                rs.getObject("created_by_proposal_id", UUID.class),
                rs.getObject("created_by_run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("retracted_at") == null ? null : rs.getTimestamp("retracted_at").toInstant());
    }

    public void save(NodeRelation relation) {
        String sql = """
                INSERT INTO node_relations (id, project_id, source_node_id, target_node_id,
                                            relation_type, origin, status, created_by_proposal_id,
                                            created_by_run_id, created_at, retracted_at)
                VALUES (:id, :projectId, :sourceNodeId, :targetNodeId,
                        :relationType, :origin, :status, :createdByProposalId,
                        :createdByRunId, :createdAt, :retractedAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", relation.id(),
                "projectId", relation.projectId(),
                "sourceNodeId", relation.sourceNodeId(),
                "targetNodeId", relation.targetNodeId(),
                "relationType", relation.relationType().code(),
                "origin", relation.origin().name(),
                "status", relation.status().name(),
                "createdByProposalId", relation.createdByProposalId(),
                "createdByRunId", relation.createdByRunId(),
                "createdAt", Timestamp.from(relation.createdAt()),
                "retractedAt", relation.retractedAt() == null ? null : Timestamp.from(relation.retractedAt())));
    }

    public Optional<NodeRelation> findById(UUID id) {
        String sql = "SELECT * FROM node_relations WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<NodeRelation> findActiveByProject(UUID projectId) {
        String sql = """
                SELECT * FROM node_relations
                WHERE project_id = :projectId AND status = 'ACTIVE'
                ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    public Optional<NodeRelation> findActive(UUID sourceNodeId, UUID targetNodeId, NodeRelationType type) {
        String sql = """
                SELECT * FROM node_relations
                WHERE source_node_id = :sourceNodeId AND target_node_id = :targetNodeId
                  AND relation_type = :relationType AND status = 'ACTIVE'
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "sourceNodeId", sourceNodeId,
                        "targetNodeId", targetNodeId,
                        "relationType", type.code()),
                rowMapper).stream().findFirst();
    }

    public void updateStatus(UUID id, NodeRelation.Status status, Instant changedAt) {
        String sql = """
                UPDATE node_relations
                SET status = :status, retracted_at = :retractedAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", id,
                "status", status.name(),
                "retractedAt", status == NodeRelation.Status.RETRACTED ? Timestamp.from(changedAt) : null));
    }

    /**
     * Inserts an active relation. A conflicting active relation with identical
     * endpoints and type is detected by a pre-check (a failed INSERT would
     * abort the surrounding PostgreSQL transaction); the partial unique index
     * remains the concurrent-write backstop.
     */
    public NodeRelation insertActiveOrThrowDuplicate(UUID projectId,
                                                     UUID sourceNodeId,
                                                     UUID targetNodeId,
                                                     NodeRelationType type,
                                                     NodeRelation.Origin origin,
                                                     UUID createdByProposalId,
                                                     UUID createdByRunId) {
        if (findActive(sourceNodeId, targetNodeId, type).isPresent()) {
            throw new IllegalStateException(
                    "An active relation of type " + type.code() + " already exists between the two nodes");
        }
        NodeRelation relation = new NodeRelation(
                Ids.random(), projectId, sourceNodeId, targetNodeId, type, origin,
                NodeRelation.Status.ACTIVE, createdByProposalId, createdByRunId, Instant.now(), null);
        save(relation);
        return relation;
    }
}
