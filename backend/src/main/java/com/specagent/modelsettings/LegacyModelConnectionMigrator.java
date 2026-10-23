package com.specagent.modelsettings;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Installation migration only; legacy rows stay readable for frozen runs. */
@Component
public class LegacyModelConnectionMigrator implements ApplicationRunner {
    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;

    public LegacyModelConnectionMigrator(NamedParameterJdbcTemplate jdbc, ModelCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        migrate("openrouter_settings", "OPENROUTER");
        migrate("custom_provider_settings", "CUSTOM");
    }

    private void migrate(String table, String code) {
        // Identifiers above are constants, never supplied by users.
        var rows = jdbc.queryForList("SELECT * FROM " + table, Map.of());
        for (var source : rows) {
            UUID org = (UUID) source.get("org_id");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM model_connection_legacy_imports WHERE org_id=:org AND source=:source)
                    """, Map.of("org",org,"source",code), Boolean.class))) continue;
            String model = (String) source.get("selected_model");
            if (model == null || model.isBlank()) continue;
            UUID id = UUID.nameUUIDFromBytes(("model-connection-v2:" + org + ":" + code)
                    .getBytes(StandardCharsets.UTF_8));
            String key = crypto.decryptScoped((String) source.get("api_key"), org, table, 1L);
            boolean router = "OPENROUTER".equals(code);
            String base = router ? "https://openrouter.ai/api/v1" : (String) source.get("base_url");
            String format = router ? "CHAT_COMPLETIONS" : (String) source.get("api_format");
            if (!router) {
                var copied = jdbc.queryForList("""
                        SELECT id FROM model_providers WHERE org_id=:org AND preset='CUSTOM'
                        AND created_at=:created AND base_url=:base ORDER BY position,created_at LIMIT 1
                        """, new MapSqlParameterSource().addValue("org",org)
                        .addValue("created",source.get("created_at")).addValue("base",base), UUID.class);
                if (!copied.isEmpty()) {
                    UUID copiedId = copied.getFirst();
                    jdbc.update("INSERT INTO model_connection_legacy_imports VALUES (:org,:source,:id) ON CONFLICT DO NOTHING",
                            Map.of("org",org,"source",code,"id",copiedId));
                    jdbc.update("""
                            UPDATE model_provider_settings SET active_provider_id=:id
                            WHERE org_id=:org AND active_provider='CUSTOM' AND active_provider_id IS NULL
                            """, Map.of("org",org,"id",copiedId));
                    continue;
                }
            }
            String name = router ? "OpenRouter" : (String) source.get("display_name");
            if (name == null || name.isBlank()) name = "Custom";
            var parameters = new MapSqlParameterSource()
                    .addValue("id", id).addValue("org", org).addValue("source", code)
                    .addValue("name", name).addValue("base", base).addValue("format", format)
                    .addValue("key", crypto.encryptScoped(key, org, "model_providers", id))
                    .addValue("suffix", source.get("masked_suffix"))
                    .addValue("model", model).addValue("revision", source.get("config_revision"))
                    .addValue("validated", router ? source.get("validated_revision") : null)
                    .addValue("validatedAt", router ? source.get("validated_at") : null);
            jdbc.update("""
                    INSERT INTO model_providers(org_id,id,preset,display_name,api_format,base_url,
                        api_key,masked_suffix,selected_model,model_source,config_revision,
                        validated_revision,validated_at,legacy_source)
                    VALUES (:org,:id,'CUSTOM',:name,:format,:base,:key,:suffix,:model,'MANUAL',
                        :revision,:validated,:validatedAt,:source)
                    ON CONFLICT DO NOTHING
                    """, parameters);
            jdbc.update("""
                    INSERT INTO model_connection_legacy_imports VALUES (:org,:source,:id)
                    ON CONFLICT DO NOTHING
                    """, parameters);
            jdbc.update("""
                    UPDATE model_provider_settings SET active_provider='CUSTOM',active_provider_id=:id
                    WHERE org_id=:org AND active_provider=:source AND active_provider_id IS NULL
                    """, parameters);
        }
    }
}
