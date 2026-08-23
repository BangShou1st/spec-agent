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
     * Atomically inserts the proposal only when no row with the same
     * idempotency key exists yet — the partial unique index is the final
     * arbiter, so concurrent creators cannot both insert and neither caller
     * sees a constraint failure. Returns true exactly when this call
     * inserted the row.
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
     * Locking read of one proposal for a lifecycle decision. The row lock is
     * held until the surrounding transaction commits or rolls back, so
     * concurrent terminal transitions on the same proposal serialize behind
     * this read and each one re-observes the committed status.
     */
    public Optional<AgentProposal> findByIdForUpdate(UUID id) {
        List<AgentProposal> results = jdbc.query(
                "SELECT * FROM agent_proposals WHERE id = :id FOR UPDATE",
                Map.of("id", id),
                MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Compare-and-set terminal transition out of PROPOSED. The conditional
     * WHERE clause is the final arbiter: exactly one concurrent caller
     * observes an affected row count of 1 (the winner); every other caller
     * sees 0 and must treat the proposal as already decided. Never overwrites
     * an existing terminal state.
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
