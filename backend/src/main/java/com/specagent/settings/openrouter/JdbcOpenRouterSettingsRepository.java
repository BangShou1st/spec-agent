package com.specagent.settings.openrouter;

import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOpenRouterSettingsRepository implements OpenRouterSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<OpenRouterSettings> rowMapper = (rs, rowNum) -> new OpenRouterSettings(
            rs.getString("api_key"),
            rs.getString("masked_suffix"),
            rs.getString("selected_model"),
            rs.getLong("config_revision"),
            rs.getObject("validated_revision") == null ? null : rs.getLong("validated_revision"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant());

    public JdbcOpenRouterSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OpenRouterSettings> find() {
        return jdbc.query("SELECT * FROM openrouter_settings WHERE singleton_id = 1", Maps.of(), rowMapper)
                .stream().findFirst();
    }

    @Override
    public void upsert(OpenRouterSettings settings) {
        Instant now = settings.updatedAt() == null ? Instant.now() : settings.updatedAt();
        jdbc.update("""
                INSERT INTO openrouter_settings (singleton_id, api_key, masked_suffix, selected_model,
                    config_revision, validated_revision, created_at, updated_at, validated_at)
                VALUES (1, :apiKey, :maskedSuffix, :selectedModel, :configRevision, :validatedRevision,
                    :createdAt, :updatedAt, :validatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    config_revision = EXCLUDED.config_revision,
                    validated_revision = EXCLUDED.validated_revision,
                    updated_at = EXCLUDED.updated_at,
                    validated_at = EXCLUDED.validated_at
                """, Maps.of(
                "apiKey", settings.apiKey(),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
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
                UPDATE openrouter_settings
                SET validated_revision = :configRevision, validated_at = :validatedAt, updated_at = :validatedAt
                WHERE singleton_id = 1 AND config_revision = :configRevision
                """, Maps.of("configRevision", configRevision, "validatedAt", Timestamp.from(now)));
    }
}
