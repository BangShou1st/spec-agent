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
    private final com.specagent.common.CapabilityCompletionPort completions;

    public CapabilityInvocationRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this(jdbcTemplate, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CapabilityInvocationRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json,
            com.specagent.common.CapabilityCompletionPort completions) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.completions = completions;
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

    /** PostgreSQL JSONB equality preserves numeric semantics and ignores object key order. */
    public boolean matchesIdentity(CapabilityInvocation invocation) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM capability_invocations
                  WHERE invocation_key=:key AND capability_id=:capability
                    AND project_id IS NOT DISTINCT FROM CAST(:project AS uuid)
                    AND run_id IS NOT DISTINCT FROM CAST(:run AS uuid)
                    AND arguments=CAST(:arguments AS jsonb))
                """,Maps.of("key",invocation.invocationKey(),"capability",invocation.capabilityId(),
                "project",invocation.projectId(),"run",invocation.runId(),
                "arguments",json.write(invocation.arguments())),Boolean.class));
    }

    @org.springframework.transaction.annotation.Transactional
    public void complete(UUID id, CapabilityResult result) {
        requireTerminalResult(id, result);
        String sql = """
                UPDATE capability_invocations
                SET status = :status, result = CAST(:result AS jsonb), completed_at = :completedAt
                WHERE id = :id AND status = 'RUNNING'
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

    /** Only terminal observations enter frozen Brain snapshots; pending jobs stay host-owned. */
    public List<CapabilityInvocationRecord> findRecentCompleted(UUID projectId, int limit) {
        String sql = """
                SELECT * FROM capability_invocations
                WHERE project_id = :projectId AND status IN ('SUCCEEDED', 'FAILED', 'REPLAYED')
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

    /** Register only an explicitly submitted external job. Deadline is chosen by its adapter. */
    public boolean markPending(UUID id, CapabilityResult result, long timeoutSeconds) {
        if (id == null || result == null || !id.equals(result.invocationId()) || result.status() != CapabilityResult.Status.PENDING
                || timeoutSeconds < 1 || timeoutSeconds > 86400) {
            throw new IllegalArgumentException("Invalid deferred capability registration");
        }
        return jdbcTemplate.update("""
                UPDATE capability_invocations
                   SET status = 'PENDING', result = CAST(:result AS jsonb),
                       pending_deadline = statement_timestamp() + make_interval(secs => :seconds)
                 WHERE id = :id AND status = 'RUNNING'
                """, Maps.of("id", id, "result", json.write(resultMap(result)),
                "seconds", timeoutSeconds)) == 1;
    }

    /** The result transition and durable continuation wake-up share one transaction. */
    @org.springframework.transaction.annotation.Transactional
    public boolean completePending(UUID id, CapabilityResult result) {
        requireTerminalResult(id, result);
        int changed = jdbcTemplate.update("""
                UPDATE capability_invocations
                   SET status = :status, result = CAST(:result AS jsonb), completed_at = clock_timestamp()
                 WHERE id = :id AND status = 'PENDING' AND pending_deadline > clock_timestamp()
                """, Maps.of("id", id, "status", result.status().name(),
                "result", json.write(resultMap(result))));
        if (changed == 1) requestContinuation(id);
        return changed == 1;
    }

    /** Bounded SKIP LOCKED scan: concurrent timeouts/completions have one row winner.
     * Persisted deadlines survive restart; no adapter or model is retried by this scan.
     */
    @org.springframework.transaction.annotation.Transactional
    public int expirePending(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid scan limit");
        List<UUID> ids = jdbcTemplate.query("""
                UPDATE capability_invocations SET status = 'FAILED', completed_at = clock_timestamp(),
                    result = jsonb_build_object('content', jsonb_build_object('reason', 'DEPENDENCY_TIMEOUT'),
                                               'sourceRefs', '[]'::jsonb, 'provenance', '{}'::jsonb,
                                               'warnings', '[]'::jsonb)
                 WHERE id IN (SELECT id FROM capability_invocations
                    WHERE status = 'PENDING' AND pending_deadline <= clock_timestamp()
                    ORDER BY pending_deadline, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                   AND status = 'PENDING' AND pending_deadline <= clock_timestamp()
                RETURNING id
                """, Maps.of("limit", limit), (rs, n) -> rs.getObject("id", UUID.class));
        ids.forEach(this::requestContinuation);
        return ids.size();
    }

    private void requestContinuation(UUID invocationId) {
        if (completions == null) throw new IllegalStateException("Deferred completion Host port is required");
        completions.completed(invocationId);
    }

    private static void requireTerminalResult(UUID id, CapabilityResult result) {
        if (id == null || result == null || !id.equals(result.invocationId())
                || (result.status() != CapabilityResult.Status.SUCCEEDED
                    && result.status() != CapabilityResult.Status.FAILED)) {
            throw new IllegalArgumentException("Expected an owned terminal capability result");
        }
    }
}
