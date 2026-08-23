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
        MapSqlParameterSource params = new MapSqlParameterSource()
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
        jdbc.update(sql, params);
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

    public void updateStatus(UUID id, ProposalStatus status, Instant decidedAt, String decidedBy) {
        jdbc.update(
                "UPDATE agent_proposals SET status = :status, decided_at = :decidedAt, decided_by = :decidedBy WHERE id = :id",
                Map.of("id", id, "status", status.code(),
                        "decidedAt", decidedAt == null ? null : java.sql.Timestamp.from(decidedAt),
                        "decidedBy", decidedBy));
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
