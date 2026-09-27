package com.specagent.workspace.route;

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
 * 文件名:RouteRepository.java
 *
 * 用途:Route 聚合的 JDBC 仓储,负责路线行的读写:保存路线、
 * 更新 tip/root 指针、更新生命周期状态,以及按 id/项目/tip 节点查询。
 */
@Repository
public class RouteRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Route> rowMapper;

    public RouteRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> new Route(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("root_node_id", UUID.class),
                rs.getObject("tip_node_id", UUID.class),
                RouteLifecycleStatus.fromCode(rs.getString("lifecycle_status")),
                rs.getString("label"),
                rs.getObject("created_from_node_id", UUID.class),
                rs.getObject("supersedes_route_id", UUID.class),
                rs.getObject("replacement_of_node_id", UUID.class),
                rs.getObject("created_by_run_id", UUID.class),
                RouteBranchType.fromCode(rs.getString("branch_type")),
                rs.getObject("source_route_id", UUID.class),
                rs.getObject("branch_at_node_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public void save(Route route) {
        String sql = """
                INSERT INTO routes (id, project_id, root_node_id, tip_node_id, lifecycle_status,
                                    label, created_from_node_id, supersedes_route_id,
                                    replacement_of_node_id, created_by_run_id, branch_type,
                                    source_route_id, branch_at_node_id, created_at, updated_at)
                VALUES (:id, :projectId, :rootNodeId, :tipNodeId, :lifecycleStatus, :label,
                        :createdFromNodeId, :supersedesRouteId, :replacementOfNodeId,
                        :createdByRunId, :branchType, :sourceRouteId, :branchAtNodeId,
                        :createdAt, :updatedAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", route.id(),
                "projectId", route.projectId(),
                "rootNodeId", route.rootNodeId(),
                "tipNodeId", route.tipNodeId(),
                "lifecycleStatus", route.lifecycleStatus().code(),
                "label", route.label(),
                "createdFromNodeId", route.createdFromNodeId(),
                "supersedesRouteId", route.supersedesRouteId(),
                "replacementOfNodeId", route.replacementOfNodeId(),
                "createdByRunId", route.createdByRunId(),
                "branchType", route.branchType() == null ? null : route.branchType().code(),
                "sourceRouteId", route.sourceRouteId(),
                "branchAtNodeId", route.branchAtNodeId(),
                "createdAt", Timestamp.from(route.createdAt()),
                "updatedAt", Timestamp.from(route.updatedAt())));
    }

    public void updateTipAndRoot(UUID routeId, UUID tipNodeId, UUID rootNodeId, Instant updatedAt) {
        String sql = """
                UPDATE routes SET tip_node_id = :tipNodeId,
                                  root_node_id = COALESCE(root_node_id, :rootNodeId),
                                  updated_at = :updatedAt
                WHERE id = :routeId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "routeId", routeId,
                "tipNodeId", tipNodeId,
                "rootNodeId", rootNodeId,
                "updatedAt", Timestamp.from(updatedAt)));
    }

    public void updateLifecycle(UUID routeId, RouteLifecycleStatus status, Instant updatedAt) {
        String sql = """
                UPDATE routes SET lifecycle_status = :status, updated_at = :updatedAt WHERE id = :routeId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "routeId", routeId,
                "status", status.code(),
                "updatedAt", Timestamp.from(updatedAt)));
    }

    /**
     * 同时清空 tip 和 root。仅用于根节点创建的撤销补偿;
     * 与 {@link #updateTipAndRoot} 不同,这里允许把 root 置空。
     */
    public void clearTipAndRoot(UUID routeId, Instant updatedAt) {
        String sql = """
                UPDATE routes SET tip_node_id = NULL, root_node_id = NULL, updated_at = :updatedAt
                WHERE id = :routeId
                """;
        jdbcTemplate.update(sql, Maps.of("routeId", routeId, "updatedAt", Timestamp.from(updatedAt)));
    }

    public List<UUID> findRouteIdsByTipNodeId(UUID tipNodeId) {
        String sql = "SELECT id FROM routes WHERE tip_node_id = :tipNodeId";
        return jdbcTemplate.queryForList(sql, Maps.of("tipNodeId", tipNodeId), UUID.class);
    }

    public Optional<Route> findById(UUID id) {
        String sql = "SELECT * FROM routes WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<Route> findByProject(UUID projectId) {
        String sql = "SELECT * FROM routes WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }
}
