package com.specagent.modelsettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名:ModelCredentialCrypto.java
 *
 * 用途:模型提供商凭据(model_providers / opencode_settings /
 * openrouter_settings / custom_provider_settings 的 api_key 列)统一的
 * AES-GCM 加密存储。存储格式:
 *
 * <pre>enc:v1:&lt;keyId8位hex&gt;:&lt;base64(iv ‖ ciphertext+tag)&gt;</pre>
 *
 * 主密钥解析顺序:
 * 1. {@code spec.agent.secret.master-key}({@code SPEC_AGENT_SECRET_MASTER_KEY},
 *    32 字节 base64)——与 MCP 连接凭据的 LocalAesSecretStore 共用同一属性;
 * 2. 未配置时使用密钥文件 {@code spec.agent.secret.master-key-file}
 *    (默认 {@code ./data/secret-master.key}),首次启动生成并持久化,
 *    之后每次启动读取同一文件,保证历史密文始终可解。
 *
 * 主密钥缺失、文件损坏或格式非法时启动直接失败(fail closed)。
 * 任何不以 {@code enc:v1:} 开头的存量值按"迁移前的明文"原样读出,
 * 由 {@link ModelCredentialEncryptionMigrator} 在启动时一次性加密改写。
 *
 * 轮换:把新密钥设为主密钥、旧密钥放入
 * {@code spec.agent.secret.master-key-fallback}(逗号分隔),启动迁移会把
 * 旧 keyId 的密文全部用新密钥重写;迁移完成后再移除 fallback。
 */
@Component
public class ModelCredentialCrypto {

    static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKey primary;
    private final String primaryId;
    private final Map<String, SecretKey> keysById = new LinkedHashMap<>();

    public ModelCredentialCrypto(
            @Value("${spec.agent.secret.master-key:${SPEC_AGENT_SECRET_MASTER_KEY:}}")
            String masterKeyB64,
            @Value("${spec.agent.secret.master-key-fallback:}") String fallbackKeysB64,
            @Value("${spec.agent.secret.master-key-file:./data/secret-master.key}")
            String keyFile) {
        List<String> raws = new ArrayList<>();
        if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
            raws.add(masterKeyB64.strip());
        } else {
            raws.add(loadOrCreateKeyFile(Path.of(keyFile)));
        }
        if (fallbackKeysB64 != null && !fallbackKeysB64.isBlank()) {
            for (String part : fallbackKeysB64.split(",")) {
                if (!part.isBlank()) {
                    raws.add(part.strip());
                }
            }
        }
        for (String raw : raws) {
            byte[] bytes = decodeKey(raw);
            String id = keyIdOf(bytes);
            if (!keysById.containsKey(id)) {
                keysById.put(id, new SecretKeySpec(bytes, "AES"));
            }
        }
        this.primaryId = keyIdOf(decodeKey(raws.get(0)));
        this.primary = keysById.get(primaryId);
    }

    /** 是否为该组件管理的密文;明文(迁移前数据)返回 false。 */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** 明文 → 密文;null/空串原样返回(无密钥列的合法空态)。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, primary, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ct, 0, payload, iv.length, ct.length);
            return PREFIX + primaryId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Model credential encryption failed", ex);
        }
    }

    /**
     * 密文 → 明文。明文(无前缀)原样返回以兼容迁移前读取;keyId 未知或
     * 认证失败(GCM tag 不匹配,例如换了错误的主密钥)一律显式失败,
     * 绝不返回垃圾数据。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        String body = stored.substring(PREFIX.length());
        int sep = body.indexOf(':');
        if (sep <= 0) {
            throw new IllegalStateException("Model credential ciphertext is malformed");
        }
        String keyId = body.substring(0, sep);
        SecretKey key = keysById.get(keyId);
        if (key == null) {
            throw new IllegalStateException(
                    "Model credential was encrypted with unknown master key id " + keyId
                            + "; restore the original key or re-enter the credential");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(body.substring(sep + 1));
            if (payload.length < IV_BYTES + 1) {
                throw new IllegalStateException("Model credential ciphertext is malformed");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Model credential decryption failed; the master key does not match "
                            + "the one used to store this credential", ex);
        }
    }

    /** 供启动迁移判断密文是否已用当前主密钥加密(否则需要轮换重写)。 */
    String keyIdOfCiphertext(String stored) {
        if (!isEncrypted(stored)) {
            return null;
        }
        String body = stored.substring(PREFIX.length());
        int sep = body.indexOf(':');
        return sep <= 0 ? null : body.substring(0, sep);
    }

    String primaryKeyId() {
        return primaryId;
    }

    private static String loadOrCreateKeyFile(Path file) {
        try {
            if (Files.exists(file)) {
                String content = Files.readString(file).strip();
                if (content.isEmpty()) {
                    throw new IllegalStateException(
                            "Master key file " + file + " is empty; restore a valid key or delete "
                                    + "it so a new one can be generated (existing encrypted "
                                    + "credentials would be lost)");
                }
                decodeKey(content);
                return content;
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String generated = Base64.getEncoder().encodeToString(raw);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, generated);
            restrictPermissionsBestEffort(file);
            return generated;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Master key file " + file + " cannot be read or created: " + ex.getMessage(), ex);
        }
    }

    private static void restrictPermissionsBestEffort(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows / 非 POSIX 文件系统没有 POSIX 权限;密钥文件继承目录 ACL。
        }
    }

    private static byte[] decodeKey(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "Master key must be base64 of exactly 32 bytes (AES-256)");
            }
            return raw;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Master key must be valid base64 encoding of 32 bytes", ex);
        }
    }

    private static String keyIdOf(byte[] keyBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
