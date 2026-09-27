package com.specagent.workspace.patch;

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

/**
 * 文件名:AnswerPatchRepository.java
 *
 * 用途:answer_patches 表的持久化访问。负责 patch(不可变记录)的
 * 写入与按路线/答案/项目维度查询,以及保序批量读取。patch 与不可变
 * 答案一一对应,多于一行的 patch 即为不变量违例。
 */
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

    /**
     * 读取一条不可变答案的全部 patch。这里刻意保留完整列表而不是用
     * first/latest 查询:多于一行的结果就是不变量违例,调用方必须
     * fail-closed。
     */
    public List<AnswerPatch> findBySourceAnswerId(UUID sourceAnswerId) {
        String sql = """
                SELECT * FROM answer_patches
                WHERE source_answer_id = :sourceAnswerId
                ORDER BY created_at, id
                """;
        return jdbcTemplate.query(sql, Maps.of("sourceAnswerId", sourceAnswerId), rowMapper);
    }

    /**
     * 返回给定 id 的 patch,并保持调用方的顺序。
     *
     * 顺序对重放至关重要:同一批 patch 以不同顺序重放可能得到不同的
     * 需求状态。本方法绝不按数据库列重新排序,只按输入列表排序;
     * 缺失的 id 直接跳过。
     */
    public List<AnswerPatch> findByIdsPreservingOrder(List<UUID> patchIds) {
        if (patchIds == null || patchIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM answer_patches WHERE id IN (:patchIds)";
        List<AnswerPatch> found = jdbcTemplate.query(sql, Maps.of("patchIds", patchIds), rowMapper);
        java.util.Map<UUID, AnswerPatch> byId = new java.util.LinkedHashMap<>();
        for (AnswerPatch p : found) {
            byId.put(p.id(), p);
        }
        List<AnswerPatch> ordered = new java.util.ArrayList<>(patchIds.size());
        for (UUID id : patchIds) {
            AnswerPatch p = byId.get(id);
            if (p != null) {
                ordered.add(p);
            }
        }
        return java.util.List.copyOf(ordered);
    }

    public Optional<AnswerPatch> findById(UUID id) {
        String sql = "SELECT * FROM answer_patches WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    /** 项目级规范扫描,仅用于重建派生的检索数据。 */
    public List<AnswerPatch> findByProject(UUID projectId) {
        String sql = """
                SELECT * FROM answer_patches WHERE project_id = :projectId ORDER BY created_at, id
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }
}
