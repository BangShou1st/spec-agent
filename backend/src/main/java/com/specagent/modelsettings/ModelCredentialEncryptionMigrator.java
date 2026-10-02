package com.specagent.modelsettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文件名:ModelCredentialEncryptionMigrator.java
 *
 * 用途:把四个模型凭据列(model_providers、opencode_settings、
 * openrouter_settings、custom_provider_settings 的 api_key)中残留的明文
 * 一次性加密,并把旧主密钥的密文轮换重写为当前主密钥。设计约束:
 *
 * - 可重复执行:已用当前主密钥加密的行原样跳过;每轮只处理明文行和
 *   keyId 不匹配的行。
 * - 失败明确恢复:迁移逐行提交,失败时该行保持明文(不丢用户配置),
 *   异常继续抛出使启动失败,而不是带着未加密数据继续运行(fail closed)。
 * - 不改表结构:列保持 TEXT,读取端按 {@code enc:v1:} 前缀区分密文与
 *   迁移前明文。
 */
@Component
public class ModelCredentialEncryptionMigrator implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCredentialEncryptionMigrator.class);

    /** (表名, 主键列)——api_key 列名在四张表中一致。 */
    private static final List<String[]> TARGETS = List.of(
            new String[] {"model_providers", "id"},
            new String[] {"opencode_settings", "singleton_id"},
            new String[] {"openrouter_settings", "singleton_id"},
            new String[] {"custom_provider_settings", "singleton_id"},
            new String[] {"search_settings", "singleton_id"},
            new String[] {"embedding_service_settings", "singleton_id"},
            new String[] {"embedding_service_revisions", "revision"});

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;

    public ModelCredentialEncryptionMigrator(NamedParameterJdbcTemplate jdbc,
                                             ModelCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = 0;
        int rotated = 0;
        for (String[] target : TARGETS) {
            String table = target[0];
            String pk = target[1];
            migrated += encryptPlaintextRows(table, pk);
            rotated += rotateForeignCiphertextRows(table, pk);
        }
        if (migrated > 0 || rotated > 0) {
            LOG.info("Model credential encryption migration: plaintextEncrypted={}, "
                    + "reencryptedForRotation={}", migrated, rotated);
        }
    }

    private int encryptPlaintextRows(String table, String pk) {
        List<Row> rows = jdbc.query(
                "SELECT " + pk + " AS pk, api_key FROM " + table
                        + " WHERE api_key IS NOT NULL AND api_key <> '' "
                        + "AND api_key NOT LIKE 'enc:v1:%'",
                Map.of(), (rs, n) -> Row.of(rs, n));
        for (Row row : rows) {
            jdbc.update("UPDATE " + table + " SET api_key = :cipher WHERE " + pk + " = :pk",
                    new MapSqlParameterSource()
                            .addValue("cipher", crypto.encrypt(row.value))
                            .addValue("pk", row.pk));
        }
        return rows.size();
    }

    private int rotateForeignCiphertextRows(String table, String pk) {
        List<Row> rows = jdbc.query(
                "SELECT " + pk + " AS pk, api_key FROM " + table
                        + " WHERE api_key LIKE 'enc:v1:%'",
                Map.of(), (rs, n) -> Row.of(rs, n));
        int changed = 0;
        for (Row row : rows) {
            if (!crypto.primaryKeyId().equals(crypto.keyIdOfCiphertext(row.value))) {
                // 用旧密钥解密(需要 fallback 配置),再用当前主密钥重写
                String plaintext = crypto.decrypt(row.value);
                jdbc.update("UPDATE " + table + " SET api_key = :cipher WHERE " + pk + " = :pk",
                        new MapSqlParameterSource()
                                .addValue("cipher", crypto.encrypt(plaintext))
                                .addValue("pk", row.pk));
                changed++;
            }
        }
        return changed;
    }

    private record Row(Object pk, String value) {
        static Row of(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            Object key = rs.getObject("pk");
            // model_providers 主键是 UUID,单例表是数字——统一按对象持有
            return new Row(key instanceof java.util.UUID u ? u
                    : (key instanceof Number n ? n.longValue() : key), rs.getString("api_key"));
        }
    }
}
