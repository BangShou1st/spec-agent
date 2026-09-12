package com.specagent.settings.custom;

import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomProviderSettingsRepository implements CustomProviderSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<CustomProviderSettings> rowMapper = (rs, rowNum) -> new CustomProviderSettings(
            rs.getString("api_format"),
            rs.getString("base_url"),
            rs.getString("api_key"),
            rs.getString("masked_suffix"),
            rs.getString("selected_model"),
            readModelSource(rs),
            rs.getLong("config_revision"),
            rs.getObject("validated_revision") == null ? null : rs.getLong("validated_revision"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant());

    public JdbcCustomProviderSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String readModelSource(java.sql.ResultSet rs) throws java.sql.SQLException {
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
                    selected_model, model_source, config_revision, validated_revision, created_at, updated_at, validated_at)
                VALUES (1, :apiFormat, :baseUrl, :apiKey, :maskedSuffix, :selectedModel,
                    :modelSource, :configRevision, :validatedRevision, :createdAt, :updatedAt, :validatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    api_format = EXCLUDED.api_format,
                    base_url = EXCLUDED.base_url,
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    model_source = EXCLUDED.model_source,
                    config_revision = EXCLUDED.config_revision,
                    validated_revision = EXCLUDED.validated_revision,
                    updated_at = EXCLUDED.updated_at,
                    validated_at = EXCLUDED.validated_at
                """, Maps.of(
                "apiFormat", settings.apiFormat(),
                "baseUrl", settings.baseUrl(),
                "apiKey", settings.apiKey(),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
                "modelSource", settings.modelSource() == null ? "DISCOVERED" : settings.modelSource(),
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
