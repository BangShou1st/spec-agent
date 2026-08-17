package com.specagent.node;

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
public class NodeRepository {

    private static final TypeReference<List<NodeOption>> NODE_OPTION_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<Node> rowMapper;

    public NodeRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new Node(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("parent_node_id", UUID.class),
                rs.getObject("created_by_run_id", UUID.class),
                rs.getObject("supersedes_node_id", UUID.class),
                rs.getString("question"),
                rs.getString("purpose"),
                json.readList(rs.getString("options"), NODE_OPTION_LIST),
                rs.getBoolean("allow_free_answer"),
                rs.getTimestamp("created_at").toInstant());
    }

    public void save(Node node) {
        String sql = """
                INSERT INTO nodes (id, project_id, parent_node_id, created_by_run_id, supersedes_node_id,
                                   question, purpose, options, allow_free_answer, created_at)
                VALUES (:id, :projectId, :parentNodeId, :createdByRunId, :supersedesNodeId,
                        :question, :purpose, CAST(:options AS jsonb), :allowFreeAnswer, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", node.id(),
                "projectId", node.projectId(),
                "parentNodeId", node.parentNodeId(),
                "createdByRunId", node.createdByRunId(),
                "supersedesNodeId", node.supersedesNodeId(),
                "question", node.question(),
                "purpose", node.purpose(),
                "options", json.writeList(node.options()),
                "allowFreeAnswer", node.allowFreeAnswer(),
                "createdAt", Timestamp.from(node.createdAt())));
    }

    public Optional<Node> findById(UUID id) {
        String sql = "SELECT * FROM nodes WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<Node> findByProject(UUID projectId) {
        String sql = "SELECT * FROM nodes WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }
}
