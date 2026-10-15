package com.specagent.assistant.conversation;

import com.specagent.access.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persisted delegation for GA, including deployments without a public runtime task. */
@Repository
public class GaRunActorRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationGuard guard;
    public GaRunActorRepository(NamedParameterJdbcTemplate jdbc, AuthorizationGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public void capture(UUID runId, UUID threadId) {
        ActorContext actor = guard.ownershipActor();
        guard.requireThreadOwnershipFor(actor, threadId);
        if (!actor.isUser()) throw AccessDeniedException.forbidden(
                AccessReason.SERVICE_NOT_AUTHORIZED, "User delegation is required");
        jdbc.update("""
                INSERT INTO ga_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source)
                VALUES(:run,:user,:org,:source)
                """, Map.of("run",runId,"user",actor.userId(),"org",actor.orgId(),
                             "source",actor.source().name()));
    }

    public ActorContext require(UUID runId) {
        return jdbc.query("""
                SELECT actor_user_id,actor_org_id,actor_source FROM ga_run_authorizations WHERE run_id=:run
                """, Map.of("run",runId), (rs,i) -> new ActorContext(
                        rs.getObject("actor_user_id",UUID.class),rs.getObject("actor_org_id",UUID.class),
                        ActorSource.valueOf(rs.getString("actor_source"))))
                .stream().findFirst().orElseThrow(() -> AccessDeniedException.forbidden(
                        AccessReason.UNAUTHENTICATED,"GA run has no persisted delegation"));
    }
}
