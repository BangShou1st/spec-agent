package com.specagent.settings.provider;

import com.specagent.common.Maps;
import com.specagent.model.provider.ModelProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Row-per-provider storage. Every provider — preset or user-defined — is a row
 * here, so adding a provider is an insert and never a schema or enum change.
 */
@Repository
public class JdbcModelProviderRepository implements ModelProviderRepository {

    private static final String COLUMNS = """
            id, preset, display_name, api_format, base_url, api_key, masked_suffix,
            selected_model, model_source, config_revision, validated_revision, position,
            created_at, updated_at, validated_at
            """;

    /** Presets first (OpenCode Zen, OpenRouter), then user rows in creation order. */
    private static final String ORDER = """
            ORDER BY CASE preset WHEN 'OPENCODE_ZEN' THEN 0 WHEN 'OPENROUTER' THEN 1 ELSE 2 END,
                     position, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ModelProviderRecord> rowMapper = JdbcModelProviderRepository::mapRow;

    public JdbcModelProviderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ModelProviderRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ModelProviderRecord(
                rs.getObject("id", UUID.class),
                ModelProvider.fromCode(rs.getString("preset")),
                rs.getString("display_name"),
                rs.getString("api_format"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("masked_suffix"),
                rs.getString("selected_model"),
                readModelSource(rs),
                rs.getLong("config_revision"),
                rs.getObject("validated_revision") == null ? null : rs.getLong("validated_revision"),
                rs.getInt("position"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant());
    }

    /** Tolerates pre-V35 rows and unknown values the same way the old reader did. */
    private static String readModelSource(ResultSet rs) throws SQLException {
        String value = rs.getString("model_source");
        if (value == null || (!"MANUAL".equals(value) && !"DISCOVERED".equals(value))) {
            return "DISCOVERED";
        }
        return value;
    }

    @Override
    public List<ModelProviderRecord> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM model_providers " + ORDER, Maps.of(), rowMapper);
    }

    @Override
    public Optional<ModelProviderRecord> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM model_providers WHERE id = :id",
                Maps.of("id", id), rowMapper).stream().findFirst();
    }

    @Override
    public Optional<ModelProviderRecord> findFirstByPreset(String preset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM model_providers WHERE preset = :preset "
                        + ORDER + " LIMIT 1",
                Maps.of("preset", preset), rowMapper).stream().findFirst();
    }

    @Override
    public void insert(ModelProviderRecord record) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO model_providers (id, preset, display_name, api_format, base_url, api_key,
                    masked_suffix, selected_model, model_source, config_revision, validated_revision,
                    position, created_at, updated_at, validated_at)
                VALUES (:id, :preset, :displayName, :apiFormat, :baseUrl, :apiKey,
                    :maskedSuffix, :selectedModel, :modelSource, :configRevision, :validatedRevision,
                    :position, :createdAt, :updatedAt, :validatedAt)
                """, Maps.of(
                "id", record.id(),
                "preset", record.preset().name(),
                "displayName", record.effectiveDisplayName(),
                "apiFormat", record.apiFormat(),
                "baseUrl", record.baseUrl(),
                "apiKey", record.apiKey(),
                "maskedSuffix", record.maskedSuffix(),
                "selectedModel", record.selectedModel(),
                "modelSource", record.modelSource() == null ? "DISCOVERED" : record.modelSource(),
                "configRevision", record.configRevision(),
                "validatedRevision", record.validatedRevision(),
                "position", record.position(),
                "createdAt", Timestamp.from(record.createdAt() == null ? now : record.createdAt()),
                "updatedAt", Timestamp.from(now),
                "validatedAt", record.validatedAt() == null ? null : Timestamp.from(record.validatedAt())));
    }

    @Override
    public void update(ModelProviderRecord record) {
        jdbc.update("""
                UPDATE model_providers SET
                    display_name = :displayName,
                    api_format = :apiFormat,
                    base_url = :baseUrl,
                    api_key = :apiKey,
                    masked_suffix = :maskedSuffix,
                    selected_model = :selectedModel,
                    model_source = :modelSource,
                    config_revision = :configRevision,
                    validated_revision = :validatedRevision,
                    position = :position,
                    updated_at = :updatedAt,
                    validated_at = :validatedAt
                WHERE id = :id
                """, Maps.of(
                "id", record.id(),
                "displayName", record.effectiveDisplayName(),
                "apiFormat", record.apiFormat(),
                "baseUrl", record.baseUrl(),
                "apiKey", record.apiKey(),
                "maskedSuffix", record.maskedSuffix(),
                "selectedModel", record.selectedModel(),
                "modelSource", record.modelSource() == null ? "DISCOVERED" : record.modelSource(),
                "configRevision", record.configRevision(),
                "validatedRevision", record.validatedRevision(),
                "position", record.position(),
                "updatedAt", Timestamp.from(Instant.now()),
                "validatedAt", record.validatedAt() == null ? null : Timestamp.from(record.validatedAt())));
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM model_providers WHERE id = :id", Maps.of("id", id));
    }

    @Override
    public void markValidated(UUID id, long configRevision) {
        Instant now = Instant.now();
        // The revision guard makes a stale test result a no-op rather than
        // blessing a configuration that changed while the probe was running.
        jdbc.update("""
                UPDATE model_providers
                SET validated_revision = :configRevision, validated_at = :validatedAt, updated_at = :validatedAt
                WHERE id = :id AND config_revision = :configRevision
                """, Maps.of("id", id, "configRevision", configRevision, "validatedAt", Timestamp.from(now)));
    }
}
