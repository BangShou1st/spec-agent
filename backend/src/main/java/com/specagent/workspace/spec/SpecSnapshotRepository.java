package com.specagent.workspace.spec;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * 文件名:SpecSnapshotRepository.java
 *
 * 用途:SpecSnapshot 的持久化仓储,基于 JDBC 访问 spec_snapshots 表,负责
 * 规格快照的写入与按 id/route 查询。章节、未决事项与来源引用均以 jsonb 列
 * 存储,读回时精确还原冻结内容。
 */
@Repository
public class SpecSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<SpecSnapshot> rowMapper;

    private static final TypeReference<List<SpecSection>> SECTION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<UnresolvedItem>> UNRESOLVED_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<SourceReference>> REF_LIST = new TypeReference<>() {
    };

    public SpecSnapshotRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new SpecSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("tip_node_id", UUID.class),
                rs.getObject("context_snapshot_id", UUID.class),
                rs.getString("format"),
                json.readList(rs.getString("sections"), SECTION_LIST),
                json.readList(rs.getString("unresolved_items"), UNRESOLVED_LIST),
                json.readList(rs.getString("source_refs"), REF_LIST),
                rs.getObject("created_by_run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    public void save(SpecSnapshot snapshot) {
        String sql = """
                INSERT INTO spec_snapshots (id, project_id, route_id, tip_node_id, context_snapshot_id,
                                            format, sections, unresolved_items, source_refs,
                                            created_by_run_id, created_at)
                VALUES (:id, :projectId, :routeId, :tipNodeId, :contextSnapshotId, :format,
                        CAST(:sections AS jsonb), CAST(:unresolvedItems AS jsonb), CAST(:sourceRefs AS jsonb),
                        :createdByRunId, :createdAt)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", snapshot.id(),
                "projectId", snapshot.projectId(),
                "routeId", snapshot.routeId(),
                "tipNodeId", snapshot.tipNodeId(),
                "contextSnapshotId", snapshot.contextSnapshotId(),
                "format", snapshot.format(),
                "sections", json.writeList(snapshot.sections()),
                "unresolvedItems", json.writeList(snapshot.unresolvedItems()),
                "sourceRefs", json.writeList(snapshot.sourceRefs()),
                "createdByRunId", snapshot.createdByRunId(),
                "createdAt", Timestamp.from(snapshot.createdAt())));
    }

    public Optional<SpecSnapshot> findById(UUID id) {
        String sql = "SELECT * FROM spec_snapshots WHERE id = :id";
        return jdbcTemplate.query(sql, Map.of("id", id), rowMapper).stream().findFirst();
    }

    public List<SpecSnapshot> findByRoute(UUID routeId) {
        String sql = "SELECT * FROM spec_snapshots WHERE route_id = :routeId ORDER BY created_at";
        return jdbcTemplate.query(sql, Map.of("routeId", routeId), rowMapper);
    }
}
