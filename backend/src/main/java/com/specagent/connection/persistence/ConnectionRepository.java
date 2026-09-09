package com.specagent.connection.persistence;

import com.specagent.common.Json;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.domain.ConnectionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable store for saved Connections. A Connection row is the product concept;
 * protocol/transport details live behind the MCP runtime and secrets behind
 * {@code SecretStore} — never in this table.
 */
@Repository
public class ConnectionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    private final RowMapper<Connection> rowMapper;

    public ConnectionRepository(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new Connection(
                rs.getObject("id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("name"),
                ConnectionKind.fromCode(rs.getString("kind")),
                ConnectionStatus.fromCode(rs.getString("status")),
                rs.getBoolean("enabled"),
                json.read(rs.getString("config"), Map.class),
                rs.getString("credential_ref"),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    public Connection insert(Connection connection) {
        String sql = """
                INSERT INTO connections
                    (id, connection_id, name, kind, status, enabled, config,
                     credential_ref, last_error, created_at, updated_at)
                VALUES
                    (:id, :connectionId, :name, :kind, :status, :enabled, :config,
                     :credentialRef, :lastError, :createdAt, :updatedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", connection.id())
                .addValue("connectionId", connection.connectionId())
                .addValue("name", connection.name())
                .addValue("kind", connection.kind().code())
                .addValue("status", connection.status().code())
                .addValue("enabled", connection.enabled())
                .addValue("config", json.write(connection.config()))
                .addValue("credentialRef", connection.credentialRef())
                .addValue("lastError", connection.lastError())
                .addValue("createdAt", Timestamp.from(connection.createdAt()))
                .addValue("updatedAt", Timestamp.from(connection.updatedAt())));
        return connection;
    }

    public Optional<Connection> findById(String connectionId) {
        String sql = "SELECT * FROM connections WHERE connection_id = :connectionId";
        return jdbc.query(sql, Map.of("connectionId", connectionId), rowMapper)
                .stream().findFirst();
    }

    public Optional<Connection> findById(UUID id) {
        String sql = "SELECT * FROM connections WHERE id = :id";
        return jdbc.query(sql, Map.of("id", id), rowMapper).stream().findFirst();
    }

    public List<Connection> list() {
        String sql = "SELECT * FROM connections ORDER BY name";
        return jdbc.query(sql, Map.of(), rowMapper);
    }

    public void updateStatus(UUID id, ConnectionStatus status, String lastError) {
        String sql = """
                UPDATE connections
                SET status = :status, last_error = :lastError, updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.code())
                .addValue("lastError", lastError)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void updateEnabled(UUID id, boolean enabled) {
        String sql = "UPDATE connections SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void updateCredentialRef(UUID id, String credentialRef) {
        String sql = "UPDATE connections SET credential_ref = :credentialRef, "
                + "updated_at = :updatedAt WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("credentialRef", credentialRef)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public Optional<Connection> findEnabledByName(String name) {
        String sql = "SELECT * FROM connections WHERE name = :name AND enabled = true";
        return jdbc.query(sql, Map.of("name", name), rowMapper).stream().findFirst();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM connections WHERE id = :id", Map.of("id", id));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}