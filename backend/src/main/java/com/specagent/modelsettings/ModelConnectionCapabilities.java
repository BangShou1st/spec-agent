package com.specagent.modelsettings;

import com.specagent.access.OrganizationConfigurationScope;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Qualifications belong to an org, connection and exact identity revision. */
@Service
public class ModelConnectionCapabilities {
    private final NamedParameterJdbcTemplate jdbc;
    private final OrganizationConfigurationScope scope;
    private final CompatibilityProbeService probe;
    public ModelConnectionCapabilities(NamedParameterJdbcTemplate jdbc,
            OrganizationConfigurationScope scope, CompatibilityProbeService probe) {
        this.jdbc = jdbc; this.scope = scope; this.probe = probe;
    }

    public Map<String, String> status(ModelProviderRecord row) {
        var result = new LinkedHashMap<String, String>();
        for (String key : new String[]{"CHAT", "STRUCTURED", "NATIVE_TOOLS"}) result.put(key, "UNTESTED");
        jdbc.query("""
                SELECT capability,status FROM model_connection_capabilities
                WHERE org_id=:org AND provider_id=:id AND config_revision=:revision
                """, params(row, false), rs -> { result.put(rs.getString(1), rs.getString(2)); });
        return Map.copyOf(result);
    }

    @org.springframework.transaction.annotation.Transactional
    public void verify(ModelProviderRecord row) {
        // Authorize before making any provider request, not after consuming quota.
        params(row, true);
        CustomApiFormat format = CustomApiFormat.fromCode(row.apiFormat());
        check(row, "CHAT", () -> probe.probeChat(format, row.baseUrl(), row.apiKey(), row.selectedModel()));
        if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
            record(row, "STRUCTURED", "UNSUPPORTED");
        } else {
            check(row, "STRUCTURED", () -> probe.probeCustom(format, row.baseUrl(), row.apiKey(), row.selectedModel()));
        }
        if (format == CustomApiFormat.CHAT_COMPLETIONS || format == CustomApiFormat.RESPONSES) {
            check(row, "NATIVE_TOOLS", () -> probe.probeNativeTools(format,
                    row.baseUrl(), row.apiKey(), row.selectedModel()));
        } else record(row, "NATIVE_TOOLS", "UNSUPPORTED");
    }

    private void check(ModelProviderRecord row, String capability, Runnable work) {
        try { work.run(); record(row, capability, "VERIFIED"); }
        catch (ModelProviderException ex) {
            // An arbitrary 400 or malformed output does not prove permanent
            // incompatibility. Only host-declared missing adapters are UNSUPPORTED.
            record(row, capability, "TEMPORARILY_UNAVAILABLE");
        } catch (IllegalArgumentException ex) {
            record(row, capability, "TEMPORARILY_UNAVAILABLE");
        }
    }

    private void record(ModelProviderRecord row, String capability, String status) {
        var parameters = params(row, true).addValue("capability", capability).addValue("status", status);
        jdbc.update("""
                INSERT INTO model_connection_capabilities(org_id,provider_id,config_revision,capability,status)
                SELECT :org,:id,:revision,:capability,:status FROM model_providers
                WHERE org_id=:org AND id=:id AND config_revision=:revision
                ON CONFLICT(org_id,provider_id,config_revision,capability)
                DO UPDATE SET status=EXCLUDED.status,checked_at=CURRENT_TIMESTAMP
                """, parameters);
    }

    private MapSqlParameterSource params(ModelProviderRecord row, boolean write) {
        return new MapSqlParameterSource().addValue("org", write ? scope.writeOrg() : scope.readOrg())
                .addValue("id", row.id()).addValue("revision", row.configRevision());
    }
}
