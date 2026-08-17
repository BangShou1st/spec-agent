package com.specagent.answer;

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
public class AnswerRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Answer> rowMapper;

    public AnswerRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> new Answer(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getString("selected_option_id"),
                rs.getString("free_text"),
                rs.getString("created_by_user"),
                rs.getTimestamp("created_at").toInstant());
    }

    public void save(Answer answer) {
        String sql = """
                INSERT INTO answers (id, project_id, route_id, node_id, selected_option_id,
                                     free_text, created_by_user, created_at)
                VALUES (:id, :projectId, :routeId, :nodeId, :selectedOptionId,
                        :freeText, :createdByUser, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", answer.id(),
                "projectId", answer.projectId(),
                "routeId", answer.routeId(),
                "nodeId", answer.nodeId(),
                "selectedOptionId", answer.selectedOptionId(),
                "freeText", answer.freeText(),
                "createdByUser", answer.createdByUser(),
                "createdAt", Timestamp.from(answer.createdAt())));
    }

    public boolean existsByRouteAndNode(UUID routeId, UUID nodeId) {
        String sql = """
                SELECT COUNT(*) FROM answers WHERE route_id = :routeId AND node_id = :nodeId
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Maps.of("routeId", routeId, "nodeId", nodeId),
                Integer.class);
        return count != null && count > 0;
    }

    public List<Answer> findByRouteAndNodeIds(UUID routeId, List<UUID> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT * FROM answers WHERE route_id = :routeId AND node_id IN (:nodeIds)
                ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of("routeId", routeId, "nodeIds", nodeIds), rowMapper);
    }

    public Optional<Answer> findById(UUID id) {
        String sql = "SELECT * FROM answers WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }
}
