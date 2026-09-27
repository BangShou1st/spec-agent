package com.specagent.agent.policy;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AgentProposalRepository.java
 *
 * 用途:动作提案(AgentProposal)的 JDBC 持久化仓库,负责提案行的写入、
 * 查询与生命周期状态流转。
 *
 * 关键并发语义:idempotency_key 部分唯一索引是幂等插入的最终仲裁者,
 * 并发创建者只会有一个插入成功;{@code findByIdForUpdate} 用行锁串行化
 * 同一提案的并发终态决策;{@code transitionFromProposed} 用条件 UPDATE
 * 实现"仅从 PROPOSED 流转"的单胜者 compare-and-set,绝不覆盖已有终态。
 *
 * 协作:被 AgentProposalService 使用,是提案生命周期数据的唯一读写层。
 */
@Repository
public class AgentProposalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<AgentProposal> MAPPER = (rs, rowNum) ->
            new AgentProposal(
                    rs.getObject("id", UUID.class),
                    rs.getObject("run_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("route_id", UUID.class),
                    rs.getString("action_family"),
                    parsePayload(rs.getString("payload_json")),
                    parseAnchorRefs(rs.getString("anchor_refs")),
                    ProposalStatus.fromCode(rs.getString("status")),
                    rs.getObject("base_context_snapshot_id", UUID.class),
                    rs.getString("base_context_hash"),
                    rs.getString("idempotency_key"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("decided_at") != null
                            ? rs.getTimestamp("decided_at").toInstant() : null,
                    rs.getString("decided_by"));

    public AgentProposalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AgentProposal proposal) {
        String sql = """
                INSERT INTO agent_proposals
                    (id, run_id, project_id, route_id, action_family,
                     payload_json, anchor_refs, status, base_context_snapshot_id,
                     base_context_hash, idempotency_key, created_at,
                     decided_at, decided_by)
                VALUES
                    (:id, :runId, :projectId, :routeId, :actionFamily,
                     CAST(:payloadJson AS jsonb), CAST(:anchorRefs AS jsonb), :status, :baseContextSnapshotId,
                     :baseContextHash, :idempotencyKey, :createdAt,
                     :decidedAt, :decidedBy)
                """;
        jdbc.update(sql, paramSource(proposal));
    }

    /**
     * 仅当不存在相同幂等键的行时才原子地插入提案——部分唯一索引是最终
     * 仲裁者,并发创建者不可能都插入成功,任何调用方也看不到约束冲突。
     * 当且仅当本次调用插入成功时返回 true。
     */
    public boolean insertIfAbsent(AgentProposal proposal) {
        String sql = """
                INSERT INTO agent_proposals
                    (id, run_id, project_id, route_id, action_family,
                     payload_json, anchor_refs, status, base_context_snapshot_id,
                     base_context_hash, idempotency_key, created_at,
                     decided_at, decided_by)
                VALUES
                    (:id, :runId, :projectId, :routeId, :actionFamily,
                     CAST(:payloadJson AS jsonb), CAST(:anchorRefs AS jsonb), :status, :baseContextSnapshotId,
                     :baseContextHash, :idempotencyKey, :createdAt,
                     :decidedAt, :decidedBy)
                ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                """;
        return jdbc.update(sql, paramSource(proposal)) == 1;
    }

    private MapSqlParameterSource paramSource(AgentProposal proposal) {
        return new MapSqlParameterSource()
                .addValue("id", proposal.id())
                .addValue("runId", proposal.runId())
                .addValue("projectId", proposal.projectId())
                .addValue("routeId", proposal.routeId())
                .addValue("actionFamily", proposal.actionFamily())
                .addValue("payloadJson", writePayload(proposal.payload()))
                .addValue("anchorRefs", writeAnchorRefs(proposal.anchorRefs()))
                .addValue("status", proposal.status().code())
                .addValue("baseContextSnapshotId", proposal.baseContextSnapshotId())
                .addValue("baseContextHash", proposal.baseContextHash())
                .addValue("idempotencyKey", proposal.idempotencyKey())
                .addValue("createdAt", java.sql.Timestamp.from(Instant.now()))
                .addValue("decidedAt", proposal.decidedAt() == null
                        ? null : java.sql.Timestamp.from(proposal.decidedAt()))
                .addValue("decidedBy", proposal.decidedBy());
    }

    public Optional<AgentProposal> findByRunId(UUID runId) {
        List<AgentProposal> results = jdbc.query(
                "SELECT * FROM agent_proposals WHERE run_id = :runId",
                Map.of("runId", runId),
                MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Optional<AgentProposal> findById(UUID id) {
        List<AgentProposal> results = jdbc.query(
                "SELECT * FROM agent_proposals WHERE id = :id",
                Map.of("id", id),
                MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Optional<AgentProposal> findByIdempotencyKey(String idempotencyKey) {
        List<AgentProposal> results = jdbc.query(
                "SELECT * FROM agent_proposals WHERE idempotency_key = :key",
                Map.of("key", idempotencyKey),
                MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<AgentProposal> findByProjectAndStatus(UUID projectId, ProposalStatus status) {
        return jdbc.query(
                "SELECT * FROM agent_proposals WHERE project_id = :projectId AND status = :status ORDER BY created_at",
                Map.of("projectId", projectId, "status", status.code()),
                MAPPER);
    }

    /**
     * 为一次生命周期决策做加锁读取。行锁会一直持有到外层事务提交或回滚,
     * 因此同一提案的并发终态流转都会在这个读取点排队串行执行,
     * 每个调用方都会重新观察到已提交的最新状态。
     */
    public Optional<AgentProposal> findByIdForUpdate(UUID id) {
        List<AgentProposal> results = jdbc.query(
                "SELECT * FROM agent_proposals WHERE id = :id FOR UPDATE",
                Map.of("id", id),
                MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 用 compare-and-set 把提案从 PROPOSED 流转到终态。条件 WHERE 子句是
     * 最终仲裁者:并发调用方中恰好有一个观察到影响行数为 1(胜者),
     * 其余调用方看到 0,必须把提案视为已被决定。绝不覆盖已有的终态。
     */
    public boolean transitionFromProposed(UUID id, ProposalStatus targetStatus,
                                          Instant decidedAt, String decidedBy) {
        int updated = jdbc.update(
                """
                UPDATE agent_proposals
                SET status = :status, decided_at = :decidedAt, decided_by = :decidedBy
                WHERE id = :id AND status = 'PROPOSED'
                """,
                Map.of("id", id, "status", targetStatus.code(),
                        "decidedAt", decidedAt == null ? null : java.sql.Timestamp.from(decidedAt),
                        "decidedBy", decidedBy));
        return updated == 1;
    }

    @SuppressWarnings("unchecked")
    private static String writePayload(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize proposal payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String writeAnchorRefs(List<String> anchorRefs) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(anchorRefs == null ? List.of() : anchorRefs);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize anchor refs", e);
        }
    }

    private static List<String> parseAnchorRefs(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        try {
            List<?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
            return parsed == null ? List.of() : parsed.stream().map(String::valueOf).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }
}
