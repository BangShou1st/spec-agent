package com.specagent.mcp;

import com.specagent.common.Json;
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
 * 文件名:McpDiscoveryCacheRepository.java
 *
 * 用途:每个连接的持久化、规范化 MCP 发现结果缓存。发现成功后,工具/资源/提示
 * 会以规范化 JSON 的形式缓存在这里,重连时无需重新执行发现;刷新(refresh)会使
 * 缓存失效。语义上属于 MCP 模块所有(表 {@code mcp_discovery_cache},
 * 以连接行 id 与当前配置版本校验)。
 */
@Repository
public class McpDiscoveryCacheRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    private final RowMapper<CacheRow> rowMapper = (rs, rowNum) -> new CacheRow(
            rs.getObject("connection_id", UUID.class),
            rs.getString("tools"),
            rs.getString("resources"),
            rs.getString("prompts"),
            rs.getString("fingerprint"),
            toInstant(rs.getTimestamp("discovered_at")));

    public McpDiscoveryCacheRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean upsert(UUID connectionId, long configurationVersion, List<?> tools, List<?> resources,
                       List<?> prompts, String fingerprint) {
        // 只在网络调用结束后持短锁，避免配置变更与迟到缓存写入交错。
        var versions = jdbc.queryForList("SELECT configuration_version FROM connections WHERE id=:id FOR SHARE",
                Map.of("id", connectionId), Long.class);
        if (versions.isEmpty() || versions.getFirst() != configurationVersion) return false;
        String sql = """
                INSERT INTO mcp_discovery_cache
                    (connection_id, configuration_version, tools, resources, prompts, fingerprint, discovered_at)
                VALUES
                    (:connectionId, :configurationVersion, :tools, :resources, :prompts, :fingerprint, :discoveredAt)
                ON CONFLICT (connection_id) DO UPDATE SET
                    configuration_version = EXCLUDED.configuration_version,
                    tools = EXCLUDED.tools,
                    resources = EXCLUDED.resources,
                    prompts = EXCLUDED.prompts,
                    fingerprint = EXCLUDED.fingerprint,
                    discovered_at = EXCLUDED.discovered_at
                """;
        return jdbc.update(sql, Map.of(
                "connectionId", connectionId,
                "configurationVersion", configurationVersion,
                "tools", json.write(tools),
                "resources", json.write(resources),
                "prompts", json.write(prompts),
                "fingerprint", fingerprint,
                "discoveredAt", Timestamp.from(Instant.now()))) == 1;
    }

    public Optional<CacheRow> findByConnection(UUID connectionId, long configurationVersion) {
        String sql = """
                SELECT cache.* FROM mcp_discovery_cache cache
                JOIN connections connection ON connection.id=cache.connection_id
                WHERE cache.connection_id=:connectionId
                AND cache.configuration_version=:configurationVersion
                AND connection.configuration_version=cache.configuration_version
                """;
        return jdbc.query(sql, Map.of("connectionId", connectionId, "configurationVersion", configurationVersion), rowMapper)
                .stream().findFirst();
    }

    public void delete(UUID connectionId) {
        jdbc.update("DELETE FROM mcp_discovery_cache WHERE connection_id = :connectionId",
                Map.of("connectionId", connectionId));
    }

    /** Caller holds the parent row in the same visibility-only mutation transaction; content/auth are unchanged. */
    public void advanceVisibilityVersion(UUID connectionId, long previousVersion) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Discovery cache visibility promotion requires an active transaction");
        jdbc.update("""
                UPDATE mcp_discovery_cache cache SET configuration_version=connection.configuration_version
                FROM connections connection
                WHERE connection.id=:id AND cache.connection_id=connection.id
                    AND cache.configuration_version=:previous
                    AND connection.configuration_version IN (:previous,:next)
                """, Map.of("id", connectionId, "previous", previousVersion, "next", previousVersion + 1));
    }

    public record CacheRow(UUID connectionId, String tools, String resources,
                           String prompts, String fingerprint, Instant discoveredAt) {
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
