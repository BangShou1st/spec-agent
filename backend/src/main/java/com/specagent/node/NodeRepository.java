package com.specagent.node;

import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NodeRepository {

    private static final TypeReference<List<NodeOption>> NODE_OPTION_LIST = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> CONTENT_MAP = new TypeReference<>() {
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
                rs.getTimestamp("created_at").toInstant(),
                NodeKind.fromCode(rs.getString("kind")),
                rs.getString("subtype"),
                json.read(rs.getString("content"), CONTENT_MAP),
                NodeAuthorKind.fromCode(rs.getString("author_kind")),
                KnowledgeStatus.fromCode(rs.getString("knowledge_status")),
                rs.getTimestamp("retracted_at") == null ? null : rs.getTimestamp("retracted_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void save(Node node) {
        String sql = """
                INSERT INTO nodes (id, project_id, parent_node_id, created_by_run_id, supersedes_node_id,
                                   question, purpose, options, allow_free_answer, created_at,
                                   kind, subtype, content, author_kind, knowledge_status,
                                   retracted_at, updated_at)
                VALUES (:id, :projectId, :parentNodeId, :createdByRunId, :supersedesNodeId,
                        :question, :purpose, CAST(:options AS jsonb), :allowFreeAnswer, :createdAt,
                        :kind, :subtype, CAST(:content AS jsonb), :authorKind, :knowledgeStatus,
                        :retractedAt, :updatedAt)
                """;
        Map<String, Object> params = baseParams(node);
        jdbcTemplate.update(sql, params);
    }

    public Optional<Node> findById(UUID id) {
        String sql = "SELECT * FROM nodes WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    /**
     * Locks the node row for the current transaction, or fails fast when the
     * node does not exist. Used to serialize concurrent mutations that must
     * decide against the node-wide state (e.g. the single-Answer invariant) —
     * the lock makes the later existence re-check authoritative instead of
     * race-prone.
     */
    public void lockById(UUID id) {
        String sql = "SELECT id FROM nodes WHERE id = :id FOR UPDATE";
        List<UUID> locked = jdbcTemplate.queryForList(sql, Maps.of("id", id), UUID.class);
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Node not found: " + id);
        }
    }

    public List<Node> findByProject(UUID projectId) {
        String sql = "SELECT * FROM nodes WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    /** In-place edit of a still-editable user draft: subtype and content only. */
    public void updateDraft(UUID nodeId, String subtype, Map<String, Object> content, Instant updatedAt) {
        String sql = """
                UPDATE nodes
                SET subtype = :subtype, content = CAST(:content AS jsonb), updated_at = :updatedAt
                WHERE id = :id
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("id", nodeId);
        params.put("subtype", subtype);
        params.put("content", json.write(content == null ? Map.of() : content));
        params.put("updatedAt", Timestamp.from(updatedAt));
        jdbcTemplate.update(sql, params);
    }

    public void updateKnowledgeStatus(UUID nodeId, KnowledgeStatus status, Instant updatedAt) {
        String sql = """
                UPDATE nodes
                SET knowledge_status = :status, updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", nodeId,
                "status", status.code(),
                "updatedAt", Timestamp.from(updatedAt)));
    }

    public void updateRetracted(UUID nodeId, Instant retractedAt) {
        String sql = """
                UPDATE nodes
                SET retracted_at = :retractedAt, updated_at = COALESCE(:retractedAt, NOW())
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of("id", nodeId, "retractedAt",
                retractedAt == null ? null : Timestamp.from(retractedAt)));
    }

    public boolean existsByParentNodeId(UUID parentNodeId) {
        String sql = "SELECT EXISTS(SELECT 1 FROM nodes WHERE parent_node_id = :parentNodeId)";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Maps.of("parentNodeId", parentNodeId), Boolean.class));
    }

    private Map<String, Object> baseParams(Node node) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", node.id());
        params.put("projectId", node.projectId());
        params.put("parentNodeId", node.parentNodeId());
        params.put("createdByRunId", node.createdByRunId());
        params.put("supersedesNodeId", node.supersedesNodeId());
        params.put("question", node.question());
        params.put("purpose", node.purpose());
        params.put("options", json.writeList(node.options()));
        params.put("allowFreeAnswer", node.allowFreeAnswer());
        params.put("createdAt", Timestamp.from(node.createdAt()));
        params.put("kind", node.kind().code());
        params.put("subtype", node.subtype());
        params.put("content", json.write(node.content()));
        params.put("authorKind", node.authorKind().code());
        params.put("knowledgeStatus", node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        params.put("retractedAt", node.retractedAt() == null ? null : Timestamp.from(node.retractedAt()));
        params.put("updatedAt", Timestamp.from(node.updatedAt()));
        return params;
    }
}
