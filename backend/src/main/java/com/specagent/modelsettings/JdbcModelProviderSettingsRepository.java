package com.specagent.modelsettings;

import com.specagent.common.Maps;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelProviderSettingsRepository implements ModelProviderSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ModelProviderSettings> rowMapper = (rs, rowNum) -> new ModelProviderSettings(
            rs.getString("active_provider"),
            rs.getObject("active_provider_id", UUID.class),
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
    public void setActive(String activeProvider, UUID activeProviderId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO model_provider_settings (singleton_id, active_provider, active_provider_id, updated_at)
                VALUES (1, :activeProvider, :activeProviderId, :updatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    active_provider = EXCLUDED.active_provider,
                    active_provider_id = EXCLUDED.active_provider_id,
                    updated_at = EXCLUDED.updated_at
                """, Maps.of(
                "activeProvider", activeProvider,
                "activeProviderId", activeProviderId,
                "updatedAt", Timestamp.from(now)));
    }
}
