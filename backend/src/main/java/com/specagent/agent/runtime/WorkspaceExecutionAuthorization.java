package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.runtime.execution.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquires execution/task protection before authorization protection, never after business locks. */
@Service
public class WorkspaceExecutionAuthorization {
    private final WorkspaceRunActorRepository actors;
    private final RuntimeTaskRepository tasks;
    private final TaskWriteGuard writes;
    private final ExecutionOwnershipMode mode;
    private final AuthorizationGuard guard;
    private final TransactionTemplate transactions;
    private final RuntimeScopeMutex scopeMutex;
    public WorkspaceExecutionAuthorization(WorkspaceRunActorRepository actors,
            RuntimeTaskRepository tasks,TaskWriteGuard writes,ExecutionOwnershipMode mode,AuthorizationGuard guard,
            PlatformTransactionManager transactions, RuntimeScopeMutex scopeMutex) {
        this.actors=actors;this.tasks=tasks;this.writes=writes;this.mode=mode;this.guard=guard;
        this.transactions=new TransactionTemplate(transactions);
        this.scopeMutex=scopeMutex;
    }
    public ActorContext authorize(UUID runId) {
        // Validation does not create a nested rollback-only marker when a control-plane caller catches refusal.
        if(TransactionSynchronizationManager.isActualTransactionActive()) return authorizeLocked(runId);
        return transactions.execute(tx->authorizeLocked(runId));
    }
    private ActorContext authorizeLocked(UUID runId) {
        if(mode.taskLevel()) {
            var token=ExecutionTokenContext.current().orElseThrow(()->new TaskOwnershipLostException("Workspace execution token is missing"));
            writes.lockOwnedTask(token);
            var task=tasks.findById(token.taskId()).orElseThrow(()->new TaskOwnershipLostException("Workspace task is missing"));
            if(task.family()!=RuntimeTaskFamily.WORKSPACE || !runId.equals(task.workspaceRunId()))
                throw new TaskOwnershipLostException("Workspace token belongs to another family or Run");
            if(!actors.requireFromOwnedTask(runId,task.actor()).equals(task.actor().toActorContext()))
                throw new TaskOwnershipLostException("Workspace Run and task delegations differ");
        } else if(tasks.findByWorkspaceRunId(runId).map(t->!t.state().isTerminal()).orElse(false)) {
            throw new TaskOwnershipLostException("A task-owned Run cannot use the legacy executor");
        }
        var actor=actors.require(runId);
        UUID project=actors.projectOf(runId);
        if (!mode.taskLevel()) scopeMutex.lock(List.of(RuntimeExecutionProtocol.projectScope(project)));
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),AuthorizationScopes.user(actor.userId()),
                AuthorizationScopes.project(project)));
        guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        return actor;
    }
}
