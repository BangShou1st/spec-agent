package com.specagent.workspace.graph;

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

/**
 * 文件名:NodeRelationRepository.java
 *
 * 用途:node_relations 表的持久化访问。管理语义关系的写入、激活
 * 关系查询、端点规范化查找与软撤回。对称关系的去重依赖数据库部分
 * 唯一索引兜底,插入前的重复预检刻意与端点顺序无关。
 */
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

    /**
     * 项目内所有与 {@code nodeId} 相连(作为 source 或 target)的 ACTIVE
     * 关系。用于节点查询的"有界 1 跳语义上下文"——调用方只保留配置内
     * 的关系类型,绝不递归。
     */
    public List<NodeRelation> findActiveTouchingNode(UUID projectId, UUID nodeId) {
        String sql = """
                SELECT * FROM node_relations
                WHERE project_id = :projectId AND status = 'ACTIVE'
                  AND (source_node_id = :nodeId OR target_node_id = :nodeId)
                ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId, "nodeId", nodeId), rowMapper);
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

    /**
     * 两节点间 ACTIVE 关系的规范化、与端点顺序无关的查找。对称类型
     * ({@code RELATED_TO}、{@code CONFLICTS_WITH})匹配时忽略端点顺序,
     * 与数据库唯一兜底索引
     * ({@code idx_node_relations_symmetric_active_unique})一致;有向类型
     * 保留创建时的方向。
     */
    public Optional<NodeRelation> findActiveByCanonicalPair(UUID projectId,
                                                            UUID nodeIdA,
                                                            UUID nodeIdB,
                                                            NodeRelationType type) {
        if (type == NodeRelationType.RELATED_TO || type == NodeRelationType.CONFLICTS_WITH) {
            // 对称事实:不管存储方向如何都匹配。由 V18 迁移规范化过的
            // 旧行可能按数据库自己的 uuid 排序(LEAST/GREATEST,按字节
            // RFC 4122 顺序)存储,这与 Java 的 UUID.compareTo(有符号
            // 64 位半段)对某些 id 不一致,因此按 Java 规范方向查询会漏掉
            // 这类行。查询同样按项目限定,与唯一索引身份一致。
            String sql = """
                    SELECT * FROM node_relations
                    WHERE project_id = :projectId
                      AND relation_type = :relationType AND status = 'ACTIVE'
                      AND ((source_node_id = :nodeIdA AND target_node_id = :nodeIdB)
                           OR (source_node_id = :nodeIdB AND target_node_id = :nodeIdA))
                    LIMIT 1
                    """;
            return jdbcTemplate.query(sql, Maps.of(
                            "projectId", projectId,
                            "relationType", type.code(),
                            "nodeIdA", nodeIdA,
                            "nodeIdB", nodeIdB),
                    rowMapper).stream().findFirst();
        }
        CanonicalEndpoints endpoints = canonicalEndpoints(nodeIdA, nodeIdB, type);
        return findActive(endpoints.sourceNodeId(), endpoints.targetNodeId(), type);
    }

    /**
     * 关系类型的端点规范化顺序。对称类型规范化为 {@code (minId, maxId)},
     * 使 {@code A -> B} 与 {@code B -> A} 映射到同一身份,与数据库兜底
     * 一致;有向类型保留创建时的方向。与
     * {@link com.specagent.workspace.graph.GraphInvariantValidator#endpointsCanonicalized}
     * 保持镜像。
     */
    public static CanonicalEndpoints canonicalEndpoints(UUID sourceNodeId, UUID targetNodeId, NodeRelationType type) {
        boolean symmetric = type == NodeRelationType.RELATED_TO || type == NodeRelationType.CONFLICTS_WITH;
        if (!symmetric || sourceNodeId.compareTo(targetNodeId) <= 0) {
            return new CanonicalEndpoints(sourceNodeId, targetNodeId);
        }
        return new CanonicalEndpoints(targetNodeId, sourceNodeId);
    }

    public record CanonicalEndpoints(UUID sourceNodeId, UUID targetNodeId) {
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
     * 插入一条激活关系。端点和类型完全相同的激活关系冲突由预检发现
     * (让 INSERT 直接失败会中止外层的 PostgreSQL 事务);并发写入的
     * 兜底仍由部分唯一索引负责。
     */
    public NodeRelation insertActiveOrThrowDuplicate(UUID projectId,
                                                     UUID sourceNodeId,
                                                     UUID targetNodeId,
                                                     NodeRelationType type,
                                                     NodeRelation.Origin origin,
                                                     UUID createdByProposalId,
                                                     UUID createdByRunId) {
        // 对称端点先规范化,使存储行使用数据库唯一兜底所要求的规范
        // (LEAST, GREATEST)身份。有向类型保留创建时的方向。对称类型的
        // 重复预检刻意与顺序无关:V18 迁移规范化过的行按数据库按字节的
        // uuid 排序存储,不保证与 Java 的 UUID.compareTo 一致,若按
        // Java 规范方向查找会漏掉既有行,导致插入撞上唯一索引、抛出
        // DuplicateKeyException,而不是受控的领域冲突。
        CanonicalEndpoints endpoints = canonicalEndpoints(sourceNodeId, targetNodeId, type);
        if (findActiveByCanonicalPair(projectId, sourceNodeId, targetNodeId, type).isPresent()) {
            throw new IllegalStateException(
                    "An active relation of type " + type.code() + " already exists between the two nodes");
        }
        NodeRelation relation = new NodeRelation(
                Ids.random(), projectId, endpoints.sourceNodeId(), endpoints.targetNodeId(), type, origin,
                NodeRelation.Status.ACTIVE, createdByProposalId, createdByRunId, Instant.now(), null);
        save(relation);
        return relation;
    }
}
