package com.specagent.connection.persistence;

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
 * Durable normalized MCP discovery cache per connection. After a successful
 * discovery, tools/resources/prompts are cached here (as normalized JSON) so
 * reconnect does not re-run discovery; refresh invalidates it.
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

    public void upsert(UUID connectionId, List<?> tools, List<?> resources,
                       List<?> prompts, String fingerprint) {
        String sql = """
                INSERT INTO mcp_discovery_cache
                    (connection_id, tools, resources, prompts, fingerprint, discovered_at)
                VALUES
                    (:connectionId, :tools, :resources, :prompts, :fingerprint, :discoveredAt)
                ON CONFLICT (connection_id) DO UPDATE SET
                    tools = EXCLUDED.tools,
                    resources = EXCLUDED.resources,
                    prompts = EXCLUDED.prompts,
                    fingerprint = EXCLUDED.fingerprint,
                    discovered_at = EXCLUDED.discovered_at
                """;
        jdbc.update(sql, Map.of(
                "connectionId", connectionId,
                "tools", json.write(tools),
                "resources", json.write(resources),
                "prompts", json.write(prompts),
                "fingerprint", fingerprint,
                "discoveredAt", Timestamp.from(Instant.now())));
    }

    public Optional<CacheRow> findByConnection(UUID connectionId) {
        String sql = "SELECT * FROM mcp_discovery_cache WHERE connection_id = :connectionId";
        return jdbc.query(sql, Map.of("connectionId", connectionId), rowMapper)
                .stream().findFirst();
    }

    public void delete(UUID connectionId) {
        jdbc.update("DELETE FROM mcp_discovery_cache WHERE connection_id = :connectionId",
                Map.of("connectionId", connectionId));
    }

    public record CacheRow(UUID connectionId, String tools, String resources,
                           String prompts, String fingerprint, Instant discoveredAt) {
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}