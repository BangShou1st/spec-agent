package com.specagent.workspace.answer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Json;
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
 * 文件名:AnswerRepository.java
 *
 * 用途:Answer 的持久化仓储,基于 JDBC 直接访问 answers 表。负责回答的写入、
 * 按 route/node/project 等维度的存在性检查与批量查询,是 answer 包与数据库之间
 * 的唯一入口。行映射中兼容 legacy 的 {@code selected_option_id} 列与 jsonb 的
 * {@code selected_option_ids} 列。
 */
@Repository
public class AnswerRepository {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<Answer> rowMapper;

    public AnswerRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> {
            String selectedOptionId = rs.getString("selected_option_id");
            String selectedOptionIdsJson = rs.getString("selected_option_ids");
            List<String> selectedOptionIds = selectedOptionIdsJson == null
                    ? (selectedOptionId == null ? List.of() : List.of(selectedOptionId))
                    : json.readList(selectedOptionIdsJson, STRING_LIST);
            return new Answer(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("route_id", UUID.class),
                    rs.getObject("node_id", UUID.class),
                    selectedOptionId,
                    selectedOptionIds,
                    rs.getString("free_text"),
                    rs.getString("created_by_user"),
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    public void save(Answer answer) {
        String sql = """
                INSERT INTO answers (id, project_id, route_id, node_id, selected_option_id,
                                     selected_option_ids, free_text, created_by_user, created_at)
                VALUES (:id, :projectId, :routeId, :nodeId, :selectedOptionId,
                        CAST(:selectedOptionIds AS jsonb), :freeText, :createdByUser, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", answer.id(),
                "projectId", answer.projectId(),
                "routeId", answer.routeId(),
                "nodeId", answer.nodeId(),
                "selectedOptionId", answer.selectedOptionId(),
                "selectedOptionIds", json.writeList(answer.selectedOptionIds()),
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

    /** 跨 route 的回答存在性检查:存在不可变回答的节点不允许被回撤。 */
    public boolean existsByNodeId(UUID nodeId) {
        String sql = "SELECT COUNT(*) FROM answers WHERE node_id = :nodeId";
        Integer count = jdbcTemplate.queryForObject(sql, Maps.of("nodeId", nodeId), Integer.class);
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

    /** 项目级全量扫描,仅用于重建派生的检索数据。 */
    public List<Answer> findByProject(UUID projectId) {
        String sql = """
                SELECT * FROM answers WHERE project_id = :projectId ORDER BY created_at, id
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }
}
