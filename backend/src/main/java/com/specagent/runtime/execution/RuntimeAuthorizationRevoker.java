package com.specagent.runtime.execution;

import com.specagent.access.AccessDeniedException;
import com.specagent.access.AuthorizationRevocationOutbox;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once outbox consumption, with task lock preceding every authorization lock. */
@Component
public class RuntimeAuthorizationRevoker {
    private final AuthorizationRevocationOutbox outbox;
    private final RuntimeTaskAuthorization authorization;
    private final RuntimeTaskScheduler scheduler;
    private final List<TaskLifecyclePort> lifecycle;
    private final JdbcTemplate jdbc;
    private final List<com.specagent.access.AuthorizationRevocationHandler> handlers;
    public RuntimeAuthorizationRevoker(AuthorizationRevocationOutbox outbox,
            RuntimeTaskAuthorization authorization,RuntimeTaskScheduler scheduler,List<TaskLifecyclePort> lifecycle,
            JdbcTemplate jdbc, List<com.specagent.access.AuthorizationRevocationHandler> handlers) {
        this.outbox=outbox;this.authorization=authorization;this.scheduler=scheduler;
        this.lifecycle=lifecycle;this.jdbc=jdbc;
        this.handlers=List.copyOf(handlers);
    }
    @Scheduled(fixedDelayString="${spec.agent.security.revocation-poll-ms:1000}")
    public void poll() {
        for(var event:outbox.pending()) {
            List<UUID> candidates=jdbc.query("""
                    SELECT id FROM runtime_tasks WHERE actor_user_id=?
                        AND (?::uuid IS NULL OR actor_org_id=?::uuid)
                        AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED') ORDER BY id
                    """,(rs,i)->rs.getObject("id",UUID.class),event.userId(),event.orgId(),event.orgId());
            for(UUID id:candidates) scheduler.cancelIf(id,lifecycle,task -> {
                try {authorization.requireTask(task);return false;}
                catch(AccessDeniedException revoked) {return true;}
            });
            for(var handler:handlers) handler.revoke(event);
            outbox.complete(event.id());
        }
    }
}
