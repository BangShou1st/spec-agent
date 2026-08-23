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
 * Durable capability invocation log. The runtime — not the model — owns
 * retry/idempotency metadata recorded here.
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
     * Atomically claims execution ownership for the invocation key: the row
     * is inserted only when no row with that key exists (the unique index is
     * the final arbiter, no check-then-insert window). Returns true exactly
     * when this call won the claim and may execute the adapter.
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

    /** Most recent completed invocations of a project (bounded observations). */
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
