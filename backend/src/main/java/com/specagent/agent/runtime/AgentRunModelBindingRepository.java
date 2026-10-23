package com.specagent.agent.runtime;

import com.specagent.common.RunModelBindingPort;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic durable binding of one Agent Run to its first model execution configuration. */
@Repository
public class AgentRunModelBindingRepository implements RunModelBindingPort {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentRunModelBindingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean bindOrVerify(UUID runId, String fingerprint) {
        if (runId == null || !RunModelBindingPort.isValidFingerprint(fingerprint)) return false;
        String sql = """
                UPDATE agent_runs
                   SET model_binding_fingerprint = COALESCE(model_binding_fingerprint, :fingerprint)
                 WHERE id = :runId
                   AND status IN ('created', 'running', 'context_built', 'model_called', 'reflected', 'persisted')
                   AND (model_binding_fingerprint IS NULL
                        OR model_binding_fingerprint = :fingerprint)
                """;
        return jdbc.update(sql, java.util.Map.of("runId", runId, "fingerprint", fingerprint)) == 1;
    }

    @Override
    public java.util.Optional<String> connectionReference(UUID runId) {
        return jdbc.query("SELECT model_connection_reference FROM agent_runs WHERE id=:id",
                java.util.Map.of("id", runId), (rs, n) -> rs.getString(1)).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public boolean bindOrVerify(UUID runId, String fingerprint, String reference) {
        if (reference == null) return bindOrVerify(runId, fingerprint);
        if (runId == null || !RunModelBindingPort.isValidFingerprint(fingerprint)
                || !RunModelBindingPort.isValidConnectionReference(reference)) return false;
        return jdbc.update("""
                UPDATE agent_runs SET
                    model_binding_fingerprint=COALESCE(model_binding_fingerprint,:fingerprint),
                    model_connection_reference=COALESCE(model_connection_reference,:reference)
                WHERE id=:id AND status IN ('created','running','context_built','model_called','reflected','persisted')
                    AND (model_binding_fingerprint IS NULL OR model_binding_fingerprint=:fingerprint)
                    AND (model_connection_reference IS NULL OR model_connection_reference=:reference)
                """, java.util.Map.of("id",runId,"fingerprint",fingerprint,"reference",reference)) == 1;
    }
}
