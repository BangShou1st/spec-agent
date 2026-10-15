package com.specagent.connection;

import com.specagent.common.Json;
import com.specagent.connection.Connection;
import com.specagent.connection.ConnectionKind;
import com.specagent.connection.ConnectionStatus;
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
 * 文件名:ConnectionRepository.java
 *
 * 用途:已保存 Connection 的持久化仓库,封装 connections 表的全部
 * 读写操作(插入、按 id/connectionId 查询、状态与凭据引用更新、删除等)。
 *
 * Connection 行是产品概念;协议/传输细节藏在 MCP 运行时之后,密钥藏在
 * {@code SecretStore} 之后——都不落这张表。
 */
@Repository
public class ConnectionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final com.specagent.connection.access.ConnectionAccess access;

    private final RowMapper<Connection> rowMapper;

    public ConnectionRepository(NamedParameterJdbcTemplate jdbc, Json json,com.specagent.connection.access.ConnectionAccess access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access=access;
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
                toInstant(rs.getTimestamp("updated_at")),rs.getObject("org_id",UUID.class),rs.getString("owner_kind"),
                rs.getObject("owner_user_id",UUID.class),rs.getString("auth_kind"),rs.getLong("configuration_version"));
    }

    @org.springframework.transaction.annotation.Transactional
    public Connection insert(Connection connection) {
        UUID org=access.creationOrg(connection.ownerKind());
        String sql = """
                INSERT INTO connections
                    (id, connection_id, name, kind, status, enabled, config,
                     credential_ref, last_error, created_at, updated_at,org_id,owner_kind,owner_user_id,auth_kind)
                VALUES
                    (:id, :connectionId, :name, :kind, :status, :enabled, :config,
                     :credentialRef, :lastError, :createdAt, :updatedAt,:org,:owner,:user,:auth)
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
                .addValue("updatedAt", Timestamp.from(connection.updatedAt()))
                .addValue("org",org).addValue("owner",connection.ownerKind())
                .addValue("user","USER".equals(connection.ownerKind())?access.actor().userId():null).addValue("auth",connection.authKind()));
        return findById(connection.id()).orElseThrow();
    }

    public Optional<Connection> findById(String connectionId) {
        String sql = "SELECT * FROM connections WHERE connection_id = :connectionId AND "+access.visible("");
        var params=access.parameters();params.put("connectionId",connectionId);
        return jdbc.query(sql, params, rowMapper)
                .stream().findFirst();
    }

    public Optional<Connection> findById(UUID id) {
        String sql = "SELECT * FROM connections WHERE id = :id AND "+access.visible("");
        var params=access.parameters();params.put("id",id);
        return jdbc.query(sql, params, rowMapper).stream().findFirst();
    }

    public List<Connection> list() {
        String sql = "SELECT * FROM connections WHERE "+access.visible("")+" ORDER BY name,connection_id";
        return jdbc.query(sql, access.parameters(), rowMapper);
    }

    public Optional<Connection> lockVisibleResultSource(UUID id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("MCP result source check requires a transaction");
        access.lockForSourceRead();
        var parameters=access.parameters();parameters.put("id",id);
        return jdbc.query("SELECT * FROM connections WHERE id=:id AND "+access.visible("")+" FOR SHARE",
                parameters,rowMapper).stream().findFirst();
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateStatus(UUID id, ConnectionStatus status, String lastError) {
        access.manage(id);
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

    @org.springframework.transaction.annotation.Transactional
    public long updateEnabled(UUID id, boolean enabled) {
        access.manage(id);
        Long previous = jdbc.queryForObject("SELECT configuration_version FROM connections WHERE id=:id FOR UPDATE",
                Map.of("id", id), Long.class);
        String sql = "UPDATE connections SET configuration_version=configuration_version+CASE WHEN enabled IS DISTINCT FROM :enabled THEN 1 ELSE 0 END, enabled = :enabled, updated_at = :updatedAt WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
        return previous;
    }

    /** 网络探测的迟到成功/失败只能修改它开始时的配置。 */
    @org.springframework.transaction.annotation.Transactional
    public boolean updateStatusIfCurrent(UUID id, long configurationVersion, ConnectionStatus status, String lastError) {
        access.manage(id);
        return jdbc.update("""
                UPDATE connections SET status=:status,last_error=:lastError,updated_at=:updatedAt
                WHERE id=:id AND configuration_version=:configurationVersion
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("configurationVersion", configurationVersion)
                .addValue("status", status.code()).addValue("lastError", lastError)
                .addValue("updatedAt", Timestamp.from(Instant.now()))) == 1;
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateCredentialRef(UUID id, String credentialRef) {
        access.manage(id);
        String sql = "UPDATE connections SET credential_ref = :credentialRef, configuration_version=configuration_version+1, auth_kind=CASE WHEN CAST(:credentialRef AS text) IS NULL THEN auth_kind ELSE 'BEARER' END, "
                + "updated_at = :updatedAt WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("credentialRef", credentialRef)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateManagement(UUID id, String name, Map<String, Object> config, String credentialRef, ConnectionStatus status, boolean enabled, String lastError) {
        access.manage(id);
        String sql = """
                UPDATE connections
                SET name = :name, configuration_version=configuration_version+CASE WHEN config IS DISTINCT FROM :config OR credential_ref IS DISTINCT FROM :credentialRef THEN 1 ELSE 0 END,
                    config = :config, credential_ref = :credentialRef,auth_kind=CASE WHEN CAST(:credentialRef AS text) IS NULL THEN auth_kind ELSE 'BEARER' END,
                    status = :status, enabled = :enabled, last_error = :lastError,
                    updated_at = :updatedAt
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("config", json.write(config == null ? Map.of() : config))
                .addValue("credentialRef", credentialRef)
                .addValue("status", status.code())
                .addValue("enabled", enabled)
                .addValue("lastError", lastError)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public Optional<Connection> findEnabledByName(String name) {
        String sql = "SELECT * FROM connections WHERE name = :name AND enabled = true AND "+access.visible("");
        var params=access.parameters();params.put("name",name);
        return jdbc.query(sql, params, rowMapper).stream().findFirst();
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(UUID id) {
        access.manage(id);
        jdbc.update("DELETE FROM connections WHERE id = :id", Map.of("id", id));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
