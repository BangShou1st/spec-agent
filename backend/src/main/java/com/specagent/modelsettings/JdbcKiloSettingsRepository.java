package com.specagent.modelsettings;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 文件名:JdbcKiloSettingsRepository.java
 *
 * 用途:KiloSettingsRepository 的 JDBC 实现。Kilo Gateway 提供商的设置在库中
 * 每个组织只有一行(org_id + singleton_id = 1),保存密钥(含脱敏后缀)与选中模型。
 * 免费模型无需密钥,因此 api_key 与 masked_suffix 可为空。
 */
@Repository
public class JdbcKiloSettingsRepository implements KiloSettingsRepository {
    private com.specagent.access.OrganizationConfigurationScope orgScope;

    @org.springframework.beans.factory.annotation.Autowired
    public void setOrgScope(com.specagent.access.OrganizationConfigurationScope orgScope) {
        this.orgScope = orgScope;
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final RowMapper<KiloSettings> rowMapper;

    public JdbcKiloSettingsRepository(NamedParameterJdbcTemplate jdbc,
                                      ModelCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.rowMapper = (rs, rowNum) -> new KiloSettings(
                crypto.decryptScoped(rs.getString("api_key"), rs.getObject("org_id", java.util.UUID.class), "kilo_settings", 1),
                rs.getString("masked_suffix"),
                rs.getString("selected_model"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    public Optional<KiloSettings> find() {
        return jdbc.query("SELECT * FROM kilo_settings WHERE singleton_id = 1 AND org_id = :orgId",
                        Maps.of("orgId", orgScope.readOrg()), rowMapper)
                .stream().findFirst();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void upsert(KiloSettings settings) {
        java.util.UUID org = orgScope.writeOrg();
        Instant now = settings.updatedAt() == null ? Instant.now() : settings.updatedAt();
        jdbc.update("""
                INSERT INTO kilo_settings (org_id, singleton_id, api_key, masked_suffix, selected_model,
                                           created_at, updated_at)
                VALUES (:orgId, 1, :apiKey, :maskedSuffix, :selectedModel, :createdAt, :updatedAt)
                ON CONFLICT (org_id, singleton_id) DO UPDATE SET
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    updated_at = EXCLUDED.updated_at
                """, Maps.of(
                "orgId", org,
                "apiKey", crypto.encryptScoped(settings.apiKey(), org, "kilo_settings", 1),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
                "createdAt", Timestamp.from(settings.createdAt() == null ? now : settings.createdAt()),
                "updatedAt", Timestamp.from(now)));
    }
}
