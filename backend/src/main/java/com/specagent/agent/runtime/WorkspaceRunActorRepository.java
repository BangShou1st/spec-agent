package com.specagent.agent.runtime;

import com.specagent.access.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable initiator delegation, also present when the public task scheduler is disabled. */
@Repository
public class WorkspaceRunActorRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationGuard guard;
    public WorkspaceRunActorRepository(NamedParameterJdbcTemplate jdbc,AuthorizationGuard guard) {
        this.jdbc=jdbc;this.guard=guard;
    }
    public ActorContext authorizeCreation(UUID project) {
        var actor=guard.ownershipActor();
        guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        if(!actor.isUser()) throw AccessDeniedException.forbidden(AccessReason.SERVICE_NOT_AUTHORIZED,
                "Workspace Run requires a user delegation");
        guard.lockSharedFailClosed(java.util.List.of(AuthorizationScopes.org(actor.orgId()),
                AuthorizationScopes.user(actor.userId()),AuthorizationScopes.project(project)));
        guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        return actor;
    }
    public void capture(UUID run,ActorContext actor) {
        jdbc.update("""
                INSERT INTO workspace_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source)
                VALUES(:run,:user,:org,:source)
                """,Map.of("run",run,"user",actor.userId(),"org",actor.orgId(),"source",actor.source().name()));
    }
    public UUID projectOf(UUID run) {
        return jdbc.query("SELECT project_id FROM agent_runs WHERE id=:run",Map.of("run",run),
                (rs,i)->rs.getObject("project_id",UUID.class)).stream().findFirst()
                .orElseThrow(()->AccessDeniedException.notVisible("Run not found"));
    }
    public ActorContext require(UUID run) {
        return jdbc.query("""
                SELECT actor_user_id,actor_org_id,actor_source FROM workspace_run_authorizations WHERE run_id=:run
                """,Map.of("run",run),(rs,i)->new ActorContext(rs.getObject("actor_user_id",UUID.class),
                        rs.getObject("actor_org_id",UUID.class),ActorSource.valueOf(rs.getString("actor_source"))))
                .stream().findFirst().orElseThrow(()->AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,
                        "Workspace Run has no persisted delegation"));
    }
    /** Upgrade an older task-backed Run using the already fenced, persisted task fact; never infer a user. */
    public ActorContext requireFromOwnedTask(UUID run,com.specagent.runtime.execution.TaskActor taskActor) {
        var actor=taskActor.toActorContext();
        guard.requireProjectFor(actor,projectOf(run),Capability.PROJECT_WRITE);
        jdbc.update("""
                INSERT INTO workspace_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source)
                VALUES(:run,:user,:org,:source) ON CONFLICT(run_id) DO NOTHING
                """,Map.of("run",run,"user",actor.userId(),"org",actor.orgId(),"source",actor.source().name()));
        return require(run);
    }
}
