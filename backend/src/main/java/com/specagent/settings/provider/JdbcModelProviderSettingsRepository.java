package com.specagent.settings.provider;

import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelProviderSettingsRepository implements ModelProviderSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ModelProviderSettings> rowMapper = (rs, rowNum) -> new ModelProviderSettings(
            rs.getString("active_provider"),
            rs.getTimestamp("updated_at").toInstant());

    public JdbcModelProviderSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ModelProviderSettings> find() {
        return jdbc.query("SELECT * FROM model_provider_settings WHERE singleton_id = 1", Maps.of(), rowMapper)
                .stream().findFirst();
    }

    @Override
    public void setActiveProvider(String activeProvider) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO model_provider_settings (singleton_id, active_provider, updated_at)
                VALUES (1, :activeProvider, :updatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    active_provider = EXCLUDED.active_provider,
                    updated_at = EXCLUDED.updated_at
                """, Maps.of("activeProvider", activeProvider, "updatedAt", Timestamp.from(now)));
    }
}
