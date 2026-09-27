package com.specagent.modelsettings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ModelCredentialEncryptionMigrationTest.java
 *
 * 测试目标:既有明文凭据的安全迁移(R4)。在隔离事务中把四个模型凭据列
 * 写入明文合成密钥,执行启动迁移,验证:
 * - 数据库物理内容不再是明文,而是 {@code enc:v1:} 密文;
 * - 通过 repository 读取返回原始明文(对上层透明);
 * - 迁移可重复执行(幂等);
 * - 通过 repository 保存的密钥同样落为密文。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelCredentialEncryptionMigrationTest {

    private static final String SYNTHETIC_KEY = "synthetic-migration-key-0123456789";

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ModelCredentialEncryptionMigrator migrator;
    @Autowired ModelCredentialCrypto crypto;
    @Autowired ModelProviderRepository modelProviders;
    @Autowired OpenCodeSettingsRepository openCode;
    @Autowired OpenRouterSettingsRepository openRouter;
    @Autowired CustomProviderSettingsRepository custom;

    private int countPlaintextRows(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE api_key = :key",
                Map.of("key", SYNTHETIC_KEY), Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void plaintextRowsAreEncryptedAndStillReadableAfterMigration() {
        // ---- model_providers:插入明文合成密钥
        UUID providerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO model_providers (id, preset, display_name, api_format, base_url, api_key,
                    masked_suffix, selected_model, model_source, config_revision, position, created_at, updated_at)
                VALUES (:id, 'CUSTOM', 'Enc migration', 'CHAT_COMPLETIONS', 'http://127.0.0.1:1/v1',
                    :key, 'K3Y9', 'm1', 'DISCOVERED', 1, 999, NOW(), NOW())
                """, Map.of("id", providerId, "key", SYNTHETIC_KEY));
        // ---- 三个单例表:更新(或插入)到明文合成密钥
        jdbc.update("""
                INSERT INTO opencode_settings (singleton_id, api_key, masked_suffix, selected_model,
                    created_at, updated_at) VALUES (1, :key, 'K3Y9', 'm1', NOW(), NOW())
                ON CONFLICT (singleton_id) DO UPDATE SET api_key = :key
                """, Map.of("key", SYNTHETIC_KEY));
        jdbc.update("""
                INSERT INTO openrouter_settings (singleton_id, api_key, masked_suffix, selected_model,
                    config_revision, created_at, updated_at) VALUES (1, :key, 'K3Y9', 'm1', 1, NOW(), NOW())
                ON CONFLICT (singleton_id) DO UPDATE SET api_key = :key
                """, Map.of("key", SYNTHETIC_KEY));
        jdbc.update("""
                INSERT INTO custom_provider_settings (singleton_id, api_format, base_url, api_key,
                    masked_suffix, selected_model, model_source, config_revision, created_at, updated_at)
                VALUES (1, 'CHAT_COMPLETIONS', 'http://127.0.0.1:1/v1', :key, 'K3Y9', 'm1',
                    'DISCOVERED', 1, NOW(), NOW())
                ON CONFLICT (singleton_id) DO UPDATE SET api_key = :key
                """, Map.of("key", SYNTHETIC_KEY));

        assertThat(countPlaintextRows("model_providers")).isEqualTo(1);
        assertThat(countPlaintextRows("opencode_settings")).isEqualTo(1);
        assertThat(countPlaintextRows("openrouter_settings")).isEqualTo(1);
        assertThat(countPlaintextRows("custom_provider_settings")).isEqualTo(1);

        migrator.run(null);

        // 数据库物理内容必须是密文,不含明文
        assertThat(countPlaintextRows("model_providers")).isZero();
        assertThat(countPlaintextRows("opencode_settings")).isZero();
        assertThat(countPlaintextRows("openrouter_settings")).isZero();
        assertThat(countPlaintextRows("custom_provider_settings")).isZero();
        Integer cipherRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_providers WHERE id = :id AND api_key LIKE 'enc:v1:%'",
                Map.of("id", providerId), Integer.class);
        assertThat(cipherRows).isEqualTo(1);

        // 读取端透明:repository 返回原始明文
        assertThat(modelProviders.findById(providerId).orElseThrow().apiKey())
                .isEqualTo(SYNTHETIC_KEY);
        assertThat(openCode.find().orElseThrow().apiKey()).isEqualTo(SYNTHETIC_KEY);
        assertThat(openRouter.find().orElseThrow().apiKey()).isEqualTo(SYNTHETIC_KEY);
        assertThat(custom.find().orElseThrow().apiKey()).isEqualTo(SYNTHETIC_KEY);

        // 幂等:重复迁移不再改动
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_providers WHERE id = :id",
                Map.of("id", providerId), Integer.class);
        migrator.run(null);
        int after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_providers WHERE id = :id",
                Map.of("id", providerId), Integer.class);
        assertThat(after).isEqualTo(before);
        // 密文保持原样(没有重新加密痕迹:值不变)
        String cipherAfterFirst = jdbc.queryForObject(
                "SELECT api_key FROM opencode_settings WHERE singleton_id = 1",
                Map.of(), String.class);
        migrator.run(null);
        assertThat(jdbc.queryForObject(
                "SELECT api_key FROM opencode_settings WHERE singleton_id = 1",
                Map.of(), String.class)).isEqualTo(cipherAfterFirst);
    }

    @Test
    void repositoryWritesAreCiphertextAtRest() {
        ModelProviderRecord record = new ModelProviderRecord(
                UUID.randomUUID(), com.specagent.model.contract.ModelProvider.CUSTOM,
                "Cipher at rest", "CHAT_COMPLETIONS", "http://127.0.0.1:1/v1",
                SYNTHETIC_KEY, "K3Y9", "m1", "DISCOVERED", 1L, null, 998,
                java.time.Instant.now(), java.time.Instant.now(), null);
        modelProviders.insert(record);
        String stored = jdbc.queryForObject(
                "SELECT api_key FROM model_providers WHERE id = :id",
                Map.of("id", record.id()), String.class);
        assertThat(stored).startsWith("enc:v1:").doesNotContain(SYNTHETIC_KEY);
        assertThat(modelProviders.findById(record.id()).orElseThrow().apiKey())
                .isEqualTo(SYNTHETIC_KEY);
    }
}
