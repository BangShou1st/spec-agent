package com.specagent.connection.credentials;

import com.specagent.common.Json;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:LocalAesSecretStore.java
 *
 * 用途:基于环境变量提供的主密钥的本地 AES-GCM 密钥存储,
 * 负责连接凭据的加密落库、解密读取、掩码后缀查询与删除。
 *
 * 密文绑定组织、连接、引用和 MCP_BEARER 用途；旧密文仅由启动迁移读取。
 * 明文绝不落库或写日志，也不跨越模型可见的边界。
 *
 * 主密钥必须通过 {@code SPEC_AGENT_SECRET_MASTER_KEY} 提供
 * (32 字节,base64 编码)。未配置时存储整体禁用,需要密钥的连接操作
 * 以类型化错误收敛失败(fail closed)。
 */
@Repository
public class LocalAesSecretStore implements SecretStore {

    static final String SECRET_PREFIX = "cred:";
    private final NamedParameterJdbcTemplate jdbc;
    private final ConnectionCredentialCrypto crypto;
    private com.specagent.connection.access.ConnectionAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAccess(com.specagent.connection.access.ConnectionAccess access) { this.access = access; }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalAesSecretStore(NamedParameterJdbcTemplate jdbc, ConnectionCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public LocalAesSecretStore(NamedParameterJdbcTemplate jdbc, Json json, String masterKeyB64) {
        this(jdbc, new ConnectionCredentialCrypto(masterKeyB64, ""));
    }

    /** 加密能力是否可用(即主密钥已配置)。 */
    public boolean available() {
        return crypto.available();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public String store(UUID connectionRowId, String secret) {
        requireSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Cannot store a blank secret");
        }
        access.manage(connectionRowId);
        var params=access.parameters();params.put("connection",connectionRowId);
        UUID org=jdbc.queryForObject("SELECT org_id FROM connections WHERE id=:connection AND "+access.visible(""),params,UUID.class);
        String ref = SECRET_PREFIX + connectionRowId + ":" + UUID.randomUUID();
        String encrypted = crypto.encrypt(secret, org, connectionRowId, ref);
        String masked = secret.length() <= 4 ? "****"
                : "****" + secret.substring(secret.length() - 4);
        String sql = """
                INSERT INTO connection_credentials
                    (ref, connection_id, encrypted_secret, masked_suffix, created_at, updated_at,org_id)
                VALUES
                    (:ref, :connectionId, :encryptedSecret, :maskedSuffix, :createdAt, :updatedAt,:org)
                ON CONFLICT (ref) DO NOTHING
                """;
        jdbc.update(sql, Map.of(
                "ref", ref,
                "connectionId", connectionRowId,
                "encryptedSecret", encrypted,
                "maskedSuffix", masked,
                "createdAt", Timestamp.from(Instant.now()),
                "updatedAt", Timestamp.from(Instant.now()),"org",org));
        return ref;
    }

    @Override
    public String resolve(String credentialRef) {
        requireSecretKey();
        var params=access.parameters();params.put("ref",credentialRef);
        return jdbc.queryForObject(
                "SELECT k.encrypted_secret,k.org_id,k.connection_id,k.ref FROM connection_credentials k "
                        + "JOIN connections c ON c.id=k.connection_id AND c.org_id=k.org_id WHERE k.ref=:ref AND " + access.visible("c"),
                params, (rs, row) -> crypto.decrypt(rs.getString("encrypted_secret"),
                        rs.getObject("org_id", UUID.class), rs.getObject("connection_id", UUID.class), rs.getString("ref")));

    }

    @Override
    public String maskedSuffix(String credentialRef) {
        var params=access.parameters();params.put("ref",credentialRef);
        try {
            return jdbc.queryForObject(
                    "SELECT k.masked_suffix FROM connection_credentials k JOIN connections c ON c.id=k.connection_id AND c.org_id=k.org_id WHERE k.ref=:ref AND "+access.visible("c"),
                    params, String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void delete(String credentialRef) {
        var params=access.parameters();params.put("ref",credentialRef);
        var ids=jdbc.queryForList("SELECT c.id FROM connection_credentials k JOIN connections c ON c.id=k.connection_id AND c.org_id=k.org_id WHERE k.ref=:ref AND "+access.visible("c"),params,UUID.class);
        if(ids.isEmpty()) return;
        access.manage(ids.getFirst());
        // 与启动迁移统一：授权范围 -> connection -> credential；FK 解绑会写父行。
        params.put("connection", ids.getFirst());
        jdbc.queryForList("SELECT id FROM connections WHERE id=:connection AND org_id=:actorOrg FOR UPDATE",
                params, UUID.class);
        jdbc.update("DELETE FROM connection_credentials WHERE ref=:ref AND org_id=:actorOrg",params);
    }

    private void requireSecretKey() { crypto.requireAvailable(); }
}
