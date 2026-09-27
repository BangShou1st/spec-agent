package com.specagent.capability;

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
 * 文件名:CapabilityInvocationRepository.java
 *
 * 用途:能力调用日志的持久化仓储(基于 PostgreSQL)。运行时——而不是模型——
 * 持有这里记录的重试/幂等元数据,用于保证外部副作用不会被重复执行。
 */
@Repository
public class CapabilityInvocationRepository {

    private static final TypeReference<Map<String, Object>> REF_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<CapabilityInvocationRecord> rowMapper;

    public CapabilityInvocationRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new CapabilityInvocationRecord(
                rs.getObject("id", UUID.class),
                rs.getString("invocation_key"),
                rs.getObject("project_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("capability_id"),
                json.read(rs.getString("arguments"), REF_MAP),
                CapabilityResult.Status.valueOf(rs.getString("status")),
                json.read(rs.getString("result"), REF_MAP),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toInstant());
    }

    /**
     * 原子性地认领 invocation key 的执行权:仅当不存在相同 key 的行时才插入
     * (由唯一索引做最终裁决,不存在"先查后插"的竞态窗口)。仅当本次调用
     * 赢得认领时返回 true,此时才允许执行适配器。
     */
    public boolean claim(CapabilityInvocation invocation) {
        String sql = """
                INSERT INTO capability_invocations
                    (id, invocation_key, project_id, run_id, capability_id, arguments, status, created_at)
                VALUES
                    (:id, :invocationKey, :projectId, :runId, :capabilityId,
                     CAST(:arguments AS jsonb), :status, :createdAt)
                ON CONFLICT (invocation_key) DO NOTHING
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "id", invocation.invocationId(),
                "invocationKey", invocation.invocationKey(),
                "projectId", invocation.projectId(),
                "runId", invocation.runId(),
                "capabilityId", invocation.capabilityId(),
                "arguments", json.write(invocation.arguments()),
                "status", "RUNNING",
                "createdAt", Timestamp.from(Instant.now()))) == 1;
    }

    public void complete(UUID id, CapabilityResult result) {
        String sql = """
                UPDATE capability_invocations
                SET status = :status, result = CAST(:result AS jsonb), completed_at = :completedAt
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", id,
                "status", result.status().name(),
                "result", json.write(resultMap(result)),
                "completedAt", Timestamp.from(Instant.now())));
    }

    public Optional<CapabilityInvocationRecord> findByInvocationKey(String invocationKey) {
        String sql = "SELECT * FROM capability_invocations WHERE invocation_key = :invocationKey";
        return jdbcTemplate.query(sql, Maps.of("invocationKey", invocationKey), rowMapper)
                .stream().findFirst();
    }

    /**
     * 按创建顺序返回一次运行拥有的全部调用记录。仅作为续跑资格判断的增量读取,
     * 不影响 claim/complete 的语义。
     */
    public List<CapabilityInvocationRecord> findByRunId(UUID runId) {
        String sql = "SELECT * FROM capability_invocations "
                + "WHERE run_id = :runId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("runId", runId), rowMapper);
    }

    /** 项目最近已完成(非 RUNNING)的调用记录,数量有界,供观测使用。 */
    public List<CapabilityInvocationRecord> findRecentCompleted(UUID projectId, int limit) {
        String sql = """
                SELECT * FROM capability_invocations
                WHERE project_id = :projectId AND status <> 'RUNNING'
                ORDER BY created_at DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId, "limit", limit), rowMapper);
    }

    private Map<String, Object> resultMap(CapabilityResult result) {
        return Map.of(
                "invocationId", result.invocationId().toString(),
                "invocationKey", result.invocationKey(),
                "capabilityId", result.capabilityId(),
                "status", result.status().name(),
                "content", result.content(),
                "sourceRefs", result.sourceRefs(),
                "provenance", result.provenance(),
                "warnings", result.warnings());
    }
}
