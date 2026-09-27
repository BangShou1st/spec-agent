package com.specagent.modelsettings;

import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:JdbcCustomProviderSettingsRepository.java
 *
 * 用途:CustomProviderSettingsRepository 的 JDBC 实现。自定义提供商配置在库中
 * 只有一行(singleton_id = 1),upsert 使用 ON CONFLICT 覆盖写;
 * markValidated 带 configRevision 条件,配置在验证期间被改过则本次验证作废。
 */
@Repository
public class JdbcCustomProviderSettingsRepository implements CustomProviderSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final RowMapper<CustomProviderSettings> rowMapper;

    public JdbcCustomProviderSettingsRepository(NamedParameterJdbcTemplate jdbc,
                                                ModelCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.rowMapper = (rs, rowNum) -> new CustomProviderSettings(
                rs.getString("api_format"),
                rs.getString("base_url"),
                crypto.decrypt(rs.getString("api_key")),
            rs.getString("masked_suffix"),
            rs.getString("selected_model"),
            readModelSource(rs),
            rs.getString("display_name"),
            rs.getLong("config_revision"),
            rs.getObject("validated_revision") == null ? null : rs.getLong("validated_revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant());
    }

    private static String readModelSource(java.sql.ResultSet rs) throws java.sql.SQLException {
        // model_source 列可能是旧表结构缺失或值非法,这里统一兜底为 DISCOVERED
        String v;
        try {
            v = rs.getString("model_source");
        } catch (java.sql.SQLException ex) {
            return "DISCOVERED";
        }
        if (v == null || (!"MANUAL".equals(v) && !"DISCOVERED".equals(v))) {
            return "DISCOVERED";
        }
        return v;
    }

    @Override
    public Optional<CustomProviderSettings> find() {
        return jdbc.query("SELECT * FROM custom_provider_settings WHERE singleton_id = 1", Maps.of(), rowMapper)
                .stream().findFirst();
    }

    @Override
    public void upsert(CustomProviderSettings settings) {
        Instant now = settings.updatedAt() == null ? Instant.now() : settings.updatedAt();
        jdbc.update("""
                INSERT INTO custom_provider_settings (singleton_id, api_format, base_url, api_key, masked_suffix,
                    selected_model, model_source, display_name, config_revision, validated_revision, created_at, updated_at, validated_at)
                VALUES (1, :apiFormat, :baseUrl, :apiKey, :maskedSuffix, :selectedModel,
                    :modelSource, :displayName, :configRevision, :validatedRevision, :createdAt, :updatedAt, :validatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    api_format = EXCLUDED.api_format,
                    base_url = EXCLUDED.base_url,
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    model_source = EXCLUDED.model_source,
                    display_name = EXCLUDED.display_name,
                    config_revision = EXCLUDED.config_revision,
                    validated_revision = EXCLUDED.validated_revision,
                    updated_at = EXCLUDED.updated_at,
                    validated_at = EXCLUDED.validated_at
                """, Maps.of(
                "apiFormat", settings.apiFormat(),
                "baseUrl", settings.baseUrl(),
                "apiKey", crypto.encrypt(settings.apiKey()),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
                "modelSource", settings.modelSource() == null ? "DISCOVERED" : settings.modelSource(),
                "displayName", settings.displayName(),
                "configRevision", settings.configRevision(),
                "validatedRevision", settings.validatedRevision(),
                "createdAt", Timestamp.from(settings.createdAt() == null ? now : settings.createdAt()),
                "updatedAt", Timestamp.from(now),
                "validatedAt", settings.validatedAt() == null ? null : Timestamp.from(settings.validatedAt())));
    }

    @Override
    public void markValidated(long configRevision) {
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE custom_provider_settings
                SET validated_revision = :configRevision, validated_at = :validatedAt, updated_at = :validatedAt
                WHERE singleton_id = 1 AND config_revision = :configRevision
                """, Maps.of("configRevision", configRevision, "validatedAt", Timestamp.from(now)));
    }
}
