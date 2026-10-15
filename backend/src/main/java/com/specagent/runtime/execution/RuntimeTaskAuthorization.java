package com.specagent.runtime.execution;

import com.specagent.access.*;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** Live delegation check after the execution task is locked, before business locks. */
@Component
public class RuntimeTaskAuthorization {
    private final RuntimeTaskRepository tasks;
    private final AuthorizationGuard guard;
    public RuntimeTaskAuthorization(RuntimeTaskRepository tasks,AuthorizationGuard guard) {
        this.tasks=tasks; this.guard=guard;
    }
    public void require(ExecutionToken token) {
        RuntimeTask task=tasks.findById(token.taskId()).orElseThrow(() ->
                new TaskOwnershipLostException("Task is missing"));
        requireTask(task);
    }
    public void requireTask(RuntimeTask task) {
        if (task.actor()==null || !task.actor().isUser() || task.actor().missingOrg())
            throw AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,
                    "Task has no user delegation");
        ActorContext actor=task.actor().toActorContext();
        var scopes=new ArrayList<String>();
        scopes.add(AuthorizationScopes.org(actor.orgId()));
        scopes.add(AuthorizationScopes.user(actor.userId()));
        if (task.family()==RuntimeTaskFamily.WORKSPACE) {
            var project=tasks.projectIdOfWorkspaceRun(task.runId()).orElseThrow(() ->
                    AccessDeniedException.notVisible("Project not found"));
            scopes.add(AuthorizationScopes.project(project));
            guard.lockSharedFailClosed(scopes);
            guard.requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        } else {
            var thread=tasks.threadIdOfGaRun(task.runId()).orElseThrow(() ->
                    AccessDeniedException.notVisible("Conversation not found"));
            scopes.add(AuthorizationScopes.thread(thread));
            guard.lockSharedFailClosed(scopes);
            guard.requireThreadOwnershipFor(actor,thread);
        }
    }
}
