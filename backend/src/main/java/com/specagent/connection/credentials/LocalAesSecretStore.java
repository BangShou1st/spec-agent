package com.specagent.connection.credentials;

import com.specagent.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:LocalAesSecretStore.java
 *
 * 用途:基于环境变量提供的主密钥的本地 AES-GCM 密钥存储,
 * 负责连接凭据的加密落库、解密读取、掩码后缀查询与删除。
 *
 * 定位是个人使用场景的最低安全形态:不接入 KMS 平台,但密钥绝不以
 * 明文落库或写日志,明文也绝不跨越模型可见的边界。
 *
 * 主密钥必须通过 {@code SPEC_AGENT_SECRET_MASTER_KEY} 提供
 * (32 字节,base64 编码)。未配置时存储整体禁用,需要密钥的连接操作
 * 以类型化错误收敛失败(fail closed)。
 */
@Repository
public class LocalAesSecretStore implements SecretStore {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    static final String SECRET_PREFIX = "cred:";

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final SecretKey masterKey;

    public LocalAesSecretStore(NamedParameterJdbcTemplate jdbc,
                               Json json,
                               @Value("${spec.agent.secret.master-key:${SPEC_AGENT_SECRET_MASTER_KEY:}}")
                               String masterKeyB64) {
        this.jdbc = jdbc;
        this.json = json;
        this.masterKey = deriveMasterKey(masterKeyB64);
    }

    private SecretKey deriveMasterKey(String masterKeyB64) {
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(masterKeyB64.strip());
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "SPEC_AGENT_SECRET_MASTER_KEY must decode to 32 bytes");
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "SPEC_AGENT_SECRET_MASTER_KEY must be base64", ex);
        }
    }

    /** 加密能力是否可用(即主密钥已配置)。 */
    public boolean available() {
        return masterKey != null;
    }

    @Override
    public String store(UUID connectionRowId, String secret) {
        requireSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Cannot store a blank secret");
        }
        String ref = SECRET_PREFIX + connectionRowId + ":" + UUID.randomUUID();
        String encrypted = encrypt(secret);
        String masked = secret.length() <= 4 ? "****"
                : "****" + secret.substring(secret.length() - 4);
        String sql = """
                INSERT INTO connection_credentials
                    (ref, connection_id, encrypted_secret, masked_suffix, created_at, updated_at)
                VALUES
                    (:ref, :connectionId, :encryptedSecret, :maskedSuffix, :createdAt, :updatedAt)
                ON CONFLICT (ref) DO NOTHING
                """;
        jdbc.update(sql, Map.of(
                "ref", ref,
                "connectionId", connectionRowId,
                "encryptedSecret", encrypted,
                "maskedSuffix", masked,
                "createdAt", Timestamp.from(Instant.now()),
                "updatedAt", Timestamp.from(Instant.now())));
        return ref;
    }

    @Override
    public String resolve(String credentialRef) {
        requireSecretKey();
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_secret FROM connection_credentials WHERE ref = :ref",
                Map.of("ref", credentialRef), String.class);
        if (encrypted == null) {
            throw new IllegalArgumentException("Unknown credential ref");
        }
        return decrypt(encrypted);
    }

    @Override
    public String maskedSuffix(String credentialRef) {
        try {
            return jdbc.queryForObject(
                    "SELECT masked_suffix FROM connection_credentials WHERE ref = :ref",
                    Map.of("ref", credentialRef), String.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Override
    public void delete(String credentialRef) {
        jdbc.update("DELETE FROM connection_credentials WHERE ref = :ref",
                Map.of("ref", credentialRef));
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Secret encryption failed", ex);
        }
    }

    private String decrypt(String encrypted) {
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(payload, IV_BYTES,
                    payload.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Secret decryption failed", ex);
        }
    }

    private void requireSecretKey() {
        if (masterKey == null) {
            throw new IllegalStateException(
                    "Secret storage is disabled: SPEC_AGENT_SECRET_MASTER_KEY not configured");
        }
    }
}