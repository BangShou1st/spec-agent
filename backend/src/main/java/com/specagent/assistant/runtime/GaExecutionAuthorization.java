package com.specagent.assistant.runtime;

import com.specagent.access.*;
import com.specagent.assistant.conversation.GaRunActorRepository;
import com.specagent.runtime.execution.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Task ownership and live authorization precede all GA Run/business locks. */
@Service
public class GaExecutionAuthorization {
    private final GaRunActorRepository actors;
    private final RuntimeTaskRepository tasks;
    private final TaskWriteGuard writes;
    private final ExecutionOwnershipMode mode;
    private final IdentityModeProperties identity;
    private final AuthorizationGuard guard;

    public GaExecutionAuthorization(GaRunActorRepository actors, RuntimeTaskRepository tasks,
            TaskWriteGuard writes, ExecutionOwnershipMode mode, IdentityModeProperties identity,
            AuthorizationGuard guard) {
        this.actors=actors; this.tasks=tasks; this.writes=writes;
        this.mode=mode; this.identity=identity; this.guard=guard;
    }

    public ActorContext lockAndAuthorize(UUID runId, long epoch) {
        var task = tasks.findByGaRunId(runId).orElse(null);
        if (mode.taskLevel()) {
            if (task == null || task.actor() == null || task.executionEpoch() != epoch)
                throw new TaskOwnershipLostException("GA task/delegation is missing or stale");
            if (task.token()==null) throw new TaskOwnershipLostException("GA task is not leased");
            writes.lockOwnedTask(task.token());
        } else if (task != null && !task.state().isTerminal()) {
            throw new TaskOwnershipLostException("Active task cannot execute through the legacy scheduler");
        }
        ActorContext actor=actors.require(runId);
        if (actor.source()==ActorSource.LOCAL && identity.accessMode()!=AccessMode.LOCAL)
            throw AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,
                    "Local delegation cannot execute in OIDC mode");
        if (mode.taskLevel()) {
            ActorContext delegated=task.actor().toActorContext();
            if (!actor.equals(delegated)) throw new TaskOwnershipLostException("GA delegation differs from task");
        }
        UUID thread=guard.resources().gaRunThread(runId).orElseThrow(() ->
                AccessDeniedException.notVisible("Run not found"));
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),
                AuthorizationScopes.user(actor.userId()),AuthorizationScopes.thread(thread)));
        guard.requireThreadOwnershipFor(actor,thread);
        return actor;
    }
}
