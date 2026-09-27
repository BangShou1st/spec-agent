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
 * 文件名:AgentInputProjectionRepository.java
 *
 * 用途:冻结模型输入投影(frozen projection)的持久化存储。每次冻结的投影
 * 就是"模型当时到底收到了什么"的不可变证据。
 *
 * 主键是 {@code snapshot_id} 并带唯一索引——每个 ContextSnapshot 只允许
 * 存在一份冻结投影,永久有效。行只写入一次(insert-if-absent),本仓库
 * 从不更新、删除,也不会基于活数据重建。
 *
 * {@code source_fingerprints} 是 TEXT 列,存放规范化 JSON。显式采用这种
 * 表示方式,避免 Flyway 存储类型与 PostgreSQL jsonb 绑定,同时仍使用严格的
 * 应用层 JSON 编解码器。
 */
@Repository
public class AgentInputProjectionRepository {

    /**
     * 投影 schema 的持久化版本号,刻意独立于跨语言 wire 信封版本。
     */
    public static final String SUPPORTED_PROJECTION_VERSION = "agent-input-projection.v2";
    /** V1 行不可变,为回放兼容保持可读。 */
    public static final String LEGACY_PROJECTION_VERSION_V1 = "agent-input-projection.v1";
    /** V20 迁移在评审修复前写入的遗留值,仅用于读取旧数据。 */
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
     * 写入冻结投影,但若该 snapshot 已存在投影则不写。仅当本次调用"赢得冻结"
     * 时返回 true;并发竞争中落败的一方必须改读胜者写入的行,
     * 而不是坚持持久化自己构建的版本。
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

    /** 一次面向模型的输入投影的不可变冻结证据。 */
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
