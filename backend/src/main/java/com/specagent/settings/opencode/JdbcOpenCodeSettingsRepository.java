package com.specagent.settings.opencode;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcOpenCodeSettingsRepository implements OpenCodeSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<OpenCodeSettings> rowMapper = (rs, rowNum) -> new OpenCodeSettings(
            rs.getString("api_key"),
            rs.getString("masked_suffix"),
            rs.getString("selected_model"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public JdbcOpenCodeSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OpenCodeSettings> find() {
        return jdbc.query("SELECT * FROM opencode_settings WHERE singleton_id = 1", Maps.of(), rowMapper)
                .stream().findFirst();
    }

    @Override
    public void upsert(OpenCodeSettings settings) {
        Instant now = settings.updatedAt() == null ? Instant.now() : settings.updatedAt();
        jdbc.update("""
                INSERT INTO opencode_settings (singleton_id, api_key, masked_suffix, selected_model,
                                               created_at, updated_at)
                VALUES (1, :apiKey, :maskedSuffix, :selectedModel, :createdAt, :updatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    updated_at = EXCLUDED.updated_at
                """, Maps.of(
                "apiKey", settings.apiKey(),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
                "createdAt", Timestamp.from(settings.createdAt() == null ? now : settings.createdAt()),
                "updatedAt", Timestamp.from(now)));
    }
}
