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
                   AND status NOT IN ('completed', 'failed')
                   AND (model_binding_fingerprint IS NULL
                        OR model_binding_fingerprint = :fingerprint)
                """;
        return jdbc.update(sql, java.util.Map.of("runId", runId, "fingerprint", fingerprint)) == 1;
    }
}
