package com.specagent.workspace.node;

import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:NodeRepository.java
 *
 * 用途:nodes 表的持久化访问。负责节点的写入与查询、行级锁、
 * 草稿原地编辑、知识状态流转、父指针(lineage 归属)更新,以及
 * 软撤回。撤回是软删除:只写 {@code retracted_at},行永不物理删除。
 */
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
                rs.getBoolean("allow_multi_select"),
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
                                   question, purpose, options, allow_free_answer, allow_multi_select, created_at,
                                   kind, subtype, content, author_kind, knowledge_status,
                                   retracted_at, updated_at)
                VALUES (:id, :projectId, :parentNodeId, :createdByRunId, :supersedesNodeId,
                        :question, :purpose, CAST(:options AS jsonb), :allowFreeAnswer, :allowMultiSelect, :createdAt,
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
     * 为当前事务锁定节点行,节点不存在时立即失败。用于串行化必须基于
     * 节点全局状态做判断的并发变更(如单一 Answer 不变量)——先加锁,
     * 后续的存在性复查才是权威的,而不是存在竞态的。
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

    /**
     * 返回给定的项目节点及其全部后代。递归查询使路线出处刷新的开销
     * 与受影响的规范前缀/材料成正比,而不是由 route service 扫全项目。
     */
    public List<Node> findDescendants(UUID projectId, Collection<UUID> rootNodeIds) {
        if (rootNodeIds == null || rootNodeIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                WITH RECURSIVE descendants(id) AS (
                    SELECT id
                    FROM nodes
                    WHERE project_id = :projectId AND id IN (:rootNodeIds)
                    UNION
                    SELECT child.id
                    FROM nodes child
                    JOIN descendants parent ON child.parent_node_id = parent.id
                    WHERE child.project_id = :projectId
                )
                SELECT * FROM nodes
                WHERE project_id = :projectId
                  AND id IN (SELECT id FROM descendants)
                ORDER BY created_at
                """, Maps.of("projectId", projectId, "rootNodeIds", rootNodeIds), rowMapper);
    }

    /** 仍可编辑的用户草稿的原地编辑:仅 subtype 与 content。 */
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

    /**
     * 设置(或清空)节点唯一的父节点——即它的 lineage 归属。
     *
     * 只有显式的 connect/disconnect 命令会用到它:节点的归属由
     * {@code parent_node_id} 链加所属路线的 tip 表达,绝不通过
     * {@code node_route} 关联表。因此调用方必须在同一事务里同步更新
     * 路线 tip。
     */
    public void updateParent(UUID nodeId, UUID parentNodeId, Instant updatedAt) {
        String sql = """
                UPDATE nodes
                SET parent_node_id = :parentNodeId, updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", nodeId,
                "parentNodeId", parentNodeId,
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

    /**
     * 与 {@link #existsByParentNodeId} 对应,但忽略已软撤回的子节点。
     * Undo/Redo 必须把"只剩已撤回后代"的节点当作叶子:否则在子节点
     * 已被撤销之后再撤销父节点会被永久拒绝,线性栈就断了(第二次撤销
     * 永远不会成功)。"存活"子节点指 {@code retracted_at} 仍为 null 的
     * 节点。
     */
    public boolean existsActiveByParentNodeId(UUID parentNodeId) {
        String sql = "SELECT EXISTS(SELECT 1 FROM nodes WHERE parent_node_id = :parentNodeId AND retracted_at IS NULL)";
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
        params.put("allowMultiSelect", node.allowMultiSelect());
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
