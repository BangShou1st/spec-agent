package com.specagent.assistant.runtime;

import com.specagent.access.*;
import com.specagent.assistant.conversation.GaRunActorRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels only invalid standalone delegations; no conversation body is read or written. */
@Component
public class GaAuthorizationRevocationHandler implements AuthorizationRevocationHandler {
    private final JdbcTemplate jdbc;
    private final AuthorizationGuard guard;
    private final GaRunActorRepository actors;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final TransactionTemplate transactions;
    public GaAuthorizationRevocationHandler(JdbcTemplate jdbc,AuthorizationGuard guard,
            GaRunActorRepository actors,GlobalAssistantRunLifecycleService lifecycle,
            PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.guard=guard;this.actors=actors;this.lifecycle=lifecycle;
        this.transactions=new TransactionTemplate(transactions);
    }
    @Override public void revoke(AuthorizationRevocationOutbox.Event event) {
        List<UUID> runs=jdbc.query("""
                SELECT r.id FROM global_assistant_runs r JOIN ga_run_authorizations a ON a.run_id=r.id
                WHERE a.actor_user_id=? AND (?::uuid IS NULL OR a.actor_org_id=?::uuid)
                  AND r.status IN ('CREATED','RUNNING')
                  AND NOT EXISTS(SELECT 1 FROM runtime_tasks t WHERE t.ga_run_id=r.id
                    AND t.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')) ORDER BY r.id
                """,(rs,i)->rs.getObject("id",UUID.class),event.userId(),event.orgId(),event.orgId());
        for(UUID run:runs) transactions.executeWithoutResult(tx -> {
            var actor=actors.require(run);
            UUID thread=guard.resources().gaRunThread(run).orElse(null);
            if(thread==null) return;
            guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),
                    AuthorizationScopes.user(actor.userId()),AuthorizationScopes.thread(thread)));
            try {guard.requireThreadOwnershipFor(actor,thread);}
            catch(AccessDeniedException revoked) {lifecycle.cancelAndTerminalize(run);}
        });
    }
}
