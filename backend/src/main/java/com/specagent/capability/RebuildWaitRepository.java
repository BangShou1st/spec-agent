package com.specagent.capability;

import com.specagent.access.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Binding is chosen by the Host, never from a model-supplied external ID. */
@Repository
public class RebuildWaitRepository {
    public record Task(UUID generation,long revision,UUID org,UUID project,String state) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationGuard guard;
    public RebuildWaitRepository(NamedParameterJdbcTemplate jdbc,AuthorizationGuard guard) {
        this.jdbc=jdbc;this.guard=guard;
    }
    public ActorContext authorize(UUID run,UUID project) {
        if(run==null || project==null) throw AccessDeniedException.notVisible("Missing rebuild wait owner");
        var actors=jdbc.query("""
            SELECT a.actor_user_id,a.actor_org_id,a.actor_source FROM workspace_run_authorizations a
            JOIN agent_runs r ON r.id=a.run_id WHERE r.id=:run AND r.project_id=:project
            """,Map.of("run",run,"project",project),(rs,n)->new ActorContext(
                rs.getObject("actor_user_id",UUID.class),rs.getObject("actor_org_id",UUID.class),
                ActorSource.valueOf(rs.getString("actor_source"))));
        if(actors.size()!=1 || !actors.getFirst().isUser() || actors.getFirst().orgId()==null)
            throw AccessDeniedException.notVisible("Missing rebuild wait delegation");
        var actor=actors.getFirst();
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),
                AuthorizationScopes.user(actor.userId()),AuthorizationScopes.project(project)));
        guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        return actor;
    }
    public Optional<Task> select(UUID project,UUID org) {
        return jdbc.query("""
            SELECT id,readiness_revision,org_id,project_id,state FROM embedding_rebuilds
            WHERE project_id=:project AND corpus_id=:project AND org_id=:org
              AND state IN ('QUEUED','RUNNING','READY')
            ORDER BY created_at DESC,id DESC LIMIT 1 FOR SHARE
            """,Map.of("project",project,"org",org),(rs,n)->new Task(rs.getObject("id",UUID.class),
                rs.getLong("readiness_revision"),rs.getObject("org_id",UUID.class),
                rs.getObject("project_id",UUID.class),rs.getString("state"))).stream().findFirst();
    }
    public void bind(UUID invocation,Task task) {
        int inserted=jdbc.update("""
            INSERT INTO capability_rebuild_waits(invocation_id,generation_id,readiness_revision)
            SELECT i.id,e.id,e.readiness_revision FROM capability_invocations i,embedding_rebuilds e
            WHERE i.id=:invocation AND i.capability_id='retrieval.await_generation' AND i.status='RUNNING'
              AND e.id=:generation AND e.readiness_revision=:revision AND e.org_id=:org
              AND e.project_id=i.project_id AND e.corpus_id=i.project_id AND e.project_id=:project
              AND e.state IN ('QUEUED','RUNNING','READY')
            """,Map.of("invocation",invocation,"generation",task.generation(),"revision",task.revision(),
                    "org",task.org(),"project",task.project()));
        if(inserted!=1) throw new IllegalStateException("REBUILD_WAIT_BINDING_REJECTED");
    }
    public Optional<Task> bound(UUID invocation) {
        return jdbc.query("""
            SELECT e.id,b.readiness_revision,e.org_id,e.project_id,o.state
            FROM capability_rebuild_waits b JOIN embedding_rebuilds e ON e.id=b.generation_id
            LEFT JOIN embedding_rebuild_outcomes o ON o.generation_id=b.generation_id
              AND o.readiness_revision=b.readiness_revision AND o.org_id=e.org_id AND o.project_id=e.project_id
            WHERE b.invocation_id=:invocation AND e.project_id=e.corpus_id
            """,Map.of("invocation",invocation),(rs,n)->new Task(rs.getObject("id",UUID.class),
                rs.getLong("readiness_revision"),rs.getObject("org_id",UUID.class),
                rs.getObject("project_id",UUID.class),rs.getString("state"))).stream().findFirst();
    }
    public List<UUID> pending(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid scan limit");
        // Include revoked owners too: they must settle safely rather than starve this scan.
        return jdbc.query("""
            UPDATE capability_rebuild_waits SET last_checked_at=clock_timestamp()
            WHERE invocation_id IN (
              SELECT b.invocation_id FROM capability_invocations i JOIN capability_rebuild_waits b ON b.invocation_id=i.id
              JOIN embedding_rebuild_outcomes o ON o.generation_id=b.generation_id
                AND o.readiness_revision=b.readiness_revision
              WHERE i.status='PENDING' AND i.capability_id='retrieval.await_generation'
              ORDER BY b.last_checked_at,i.id LIMIT :limit FOR UPDATE OF b SKIP LOCKED)
            RETURNING invocation_id
            """,Map.of("limit",limit),(rs,n)->rs.getObject("id",UUID.class));
    }
}
