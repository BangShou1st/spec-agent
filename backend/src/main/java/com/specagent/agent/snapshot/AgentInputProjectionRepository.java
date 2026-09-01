package com.specagent.agent.snapshot;

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
 * Durable store for frozen model-input projections: the immutable, per-
 * snapshot evidence of exactly what the model received.
 *
 * <p>Identity is {@code snapshot_id} with a unique index — one frozen
 * projection per ContextSnapshot, forever. Rows are written once via
 * insert-if-absent and are never updated, deleted, or rebuilt from live
 * records by this repository.
 *
 * <p>{@code source_fingerprints} is a TEXT column containing canonical JSON.
 * Keeping that representation explicit avoids coupling Flyway storage type to
 * PostgreSQL jsonb while still using the strict application JSON codec.
 */
@Repository
public class AgentInputProjectionRepository {

    /**
     * Durable projection schema version, intentionally independent of the
     * cross-language wire envelope version.
     */
    public static final String SUPPORTED_PROJECTION_VERSION = "agent-input-projection.v1";
    /** Legacy value shipped in V20 before the review fix. Accepted for reading old rows. */
    private static final String LEGACY_PROJECTION_VERSION = "agent-input.v2";

    private static final TypeReference<List<MutableSourceFingerprint>> FINGERPRINT_LIST =
            new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<FrozenInputProjection> rowMapper;

    public AgentInputProjectionRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new FrozenInputProjection(
                rs.getObject("id", UUID.class),
                rs.getObject("snapshot_id", UUID.class),
                rs.getString("projection_version"),
                rs.getString("payload"),
                rs.getString("payload_hash"),
                readFingerprints(rs.getString("source_fingerprints")),
                rs.getTimestamp("created_at").toInstant());
    }

    private List<MutableSourceFingerprint> readFingerprints(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return List.of();
        }
        try {
            List<MutableSourceFingerprint> list = json.read(raw, FINGERPRINT_LIST);
            return list == null ? List.of() : List.copyOf(list);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public Optional<FrozenInputProjection> findBySnapshotId(UUID snapshotId) {
        String sql = "SELECT * FROM agent_input_projections WHERE snapshot_id = :snapshotId";
        return jdbcTemplate.query(sql, Map.of("snapshotId", snapshotId), rowMapper)
                .stream().findFirst();
    }

    /**
     * Writes the frozen projection unless one already exists for the snapshot.
     * Returns true exactly when this call won the freeze; a concurrent loser
     * must reload the winner's row instead of persisting its own build.
     */
    public boolean insertIfAbsent(FrozenInputProjection projection) {
        String sql = """
                INSERT INTO agent_input_projections
                    (id, snapshot_id, projection_version, payload, payload_hash, source_fingerprints, created_at)
                VALUES
                    (:id, :snapshotId, :projectionVersion, :payload, :payloadHash, :sourceFingerprints, :createdAt)
                ON CONFLICT (snapshot_id) DO NOTHING
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "id", projection.id(),
                "snapshotId", projection.snapshotId(),
                "projectionVersion", projection.projectionVersion(),
                "payload", projection.payload(),
                "payloadHash", projection.payloadHash(),
                "sourceFingerprints", json.write(projection.sourceFingerprints()),
                "createdAt", Timestamp.from(projection.createdAt()))) == 1;
    }

    /** Immutable frozen evidence of one model-facing input projection. */
    public record FrozenInputProjection(UUID id,
                                        UUID snapshotId,
                                        String projectionVersion,
                                        String payload,
                                        String payloadHash,
                                        List<MutableSourceFingerprint> sourceFingerprints,
                                        Instant createdAt) {
        public FrozenInputProjection {
            sourceFingerprints = sourceFingerprints == null ? List.of() : List.copyOf(sourceFingerprints);
        }
    }
}
