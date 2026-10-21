package com.specagent.retrieval.workload;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable terminal fact, committed with the real rebuild transition. First terminal
 * outcome wins: discarding an already READY generation cannot undo readiness.
 * No HTTP completion endpoint, network call, or independent scheduler. */
@Repository
public class RebuildReadiness {
    private final NamedParameterJdbcTemplate jdbc;
    public RebuildReadiness(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(UUID id) {
        jdbc.update("""
            INSERT INTO embedding_rebuild_outcomes(generation_id,readiness_revision,org_id,project_id,state)
            SELECT id,readiness_revision,org_id,project_id,state FROM embedding_rebuilds
            WHERE id=:id AND project_id IS NOT NULL AND corpus_id=project_id
              AND state IN ('READY','ACTIVE','FAILED','DISCARDED')
            ON CONFLICT(generation_id,readiness_revision) DO NOTHING
            """,Map.of("id",id));
    }
}
