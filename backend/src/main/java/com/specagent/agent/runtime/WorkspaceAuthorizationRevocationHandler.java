package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.agent.runevent.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Standalone Run convergence after a committed revocation; reads only delegation/project identifiers. */
@Component
public class WorkspaceAuthorizationRevocationHandler implements AuthorizationRevocationHandler {
    private final JdbcTemplate jdbc;
    private final WorkspaceRunActorRepository actors;
    private final AuthorizationGuard guard;
    private final AgentRunRepository runs;
    private final AgentRunEventService events;
    private final TransactionTemplate transactions;
    public WorkspaceAuthorizationRevocationHandler(JdbcTemplate jdbc,WorkspaceRunActorRepository actors,
            AuthorizationGuard guard,AgentRunRepository runs,AgentRunEventService events,
            PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.actors=actors;this.guard=guard;this.runs=runs;this.events=events;
        this.transactions=new TransactionTemplate(transactions);
    }
    @Override public void revoke(AuthorizationRevocationOutbox.Event event) {
        List<UUID> candidates=jdbc.query("""
                SELECT r.id FROM agent_runs r JOIN workspace_run_authorizations a ON a.run_id=r.id
                WHERE a.actor_user_id=? AND (?::uuid IS NULL OR a.actor_org_id=?::uuid)
                  AND r.status NOT IN ('completed','failed')
                  AND NOT EXISTS(SELECT 1 FROM runtime_tasks t WHERE t.workspace_run_id=r.id
                    AND t.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')) ORDER BY r.id
                """,(rs,i)->rs.getObject("id",UUID.class),event.userId(),event.orgId(),event.orgId());
        for(UUID run:candidates) transactions.executeWithoutResult(tx -> {
            var actor=actors.require(run);
            UUID project=actors.projectOf(run);
            guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),AuthorizationScopes.user(actor.userId()),
                    AuthorizationScopes.project(project)));
            try {guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);}
            catch(AccessDeniedException revoked) {
                if(runs.fail(run,"failed:AUTHORIZATION_REVOKED",0)==1)
                    events.append(run,AgentRunPhase.FAILED,"RUN_FAILED",java.util.Map.of("reason","AUTHORIZATION_REVOKED"));
            }
        });
    }
}
