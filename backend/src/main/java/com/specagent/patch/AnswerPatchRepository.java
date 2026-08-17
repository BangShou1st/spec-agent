package com.specagent.patch;

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
public class AnswerPatchRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<AnswerPatch> rowMapper;

    private static final TypeReference<List<Claim>> CLAIM_LIST = new TypeReference<>() {
    };

    public AnswerPatchRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new AnswerPatch(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("source_node_id", UUID.class),
                rs.getObject("source_answer_id", UUID.class),
                json.readList(rs.getString("claims"), CLAIM_LIST),
                rs.getObject("created_by_run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    public void save(AnswerPatch patch) {
        String sql = """
                INSERT INTO answer_patches (id, project_id, route_id, source_node_id,
                                            source_answer_id, claims, created_by_run_id, created_at)
                VALUES (:id, :projectId, :routeId, :sourceNodeId, :sourceAnswerId,
                        CAST(:claims AS jsonb), :createdByRunId, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", patch.id(),
                "projectId", patch.projectId(),
                "routeId", patch.routeId(),
                "sourceNodeId", patch.sourceNodeId(),
                "sourceAnswerId", patch.sourceAnswerId(),
                "claims", json.writeList(patch.claims()),
                "createdByRunId", patch.createdByRunId(),
                "createdAt", Timestamp.from(patch.createdAt())));
    }

    public List<AnswerPatch> findByRoute(UUID routeId) {
        String sql = "SELECT * FROM answer_patches WHERE route_id = :routeId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("routeId", routeId), rowMapper);
    }

    public List<AnswerPatch> findBySourceAnswerIds(List<UUID> answerIds) {
        if (answerIds == null || answerIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT * FROM answer_patches WHERE source_answer_id IN (:answerIds) ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of("answerIds", answerIds), rowMapper);
    }

    public Optional<AnswerPatch> findById(UUID id) {
        String sql = "SELECT * FROM answer_patches WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }
}
