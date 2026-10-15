package com.specagent.retrieval.protocol;

import com.specagent.access.*;
import com.specagent.runtime.execution.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads delegation only from Host-issued grant facts; RPC filters cannot manufacture an actor. */
@Service
public class RetrievalGrantAuthorization {
    private final AuthorizationGuard guard;
    private final IdentityModeProperties mode;
    private final TaskWriteGuard writes;
    private final RuntimeTaskRepository tasks;
    public RetrievalGrantAuthorization(AuthorizationGuard guard,IdentityModeProperties mode,
            TaskWriteGuard writes,RuntimeTaskRepository tasks) {
        this.guard=guard;this.mode=mode;this.writes=writes;this.tasks=tasks;
    }
    public record Delegation(ActorContext actor,ExecutionToken token) {}
    public record IndexService(String kind,UUID org) {}

    @Transactional
    public IndexService indexService(UUID corpus) {
        if(CuratedHelpSources.CORPUS.equals(corpus)) return new IndexService("SYSTEM_HELP_INDEXING",null);
        UUID org=guard.resources().projectOrg(corpus).orElseThrow(()->new IllegalStateException("STALE_SCOPE_GRANT"));
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(org),AuthorizationScopes.project(corpus)));
        try {guard.requireOrg(ActorContext.system(org),org,null);}
        catch(AccessDeniedException refused) {throw new IllegalStateException("STALE_SCOPE_GRANT");}
        return new IndexService("SYSTEM_INDEXING",org);
    }

    @Transactional
    public void validateIndexService(UUID corpus,UUID project,Map<String,Object> row) {
        boolean help=CuratedHelpSources.CORPUS.equals(corpus);
        if(help) {
            if(project!=null || row.get("service_org_id")!=null || !"SYSTEM_HELP_INDEXING".equals(row.get("service_kind")))
                throw new IllegalStateException("STALE_SCOPE_GRANT");
        } else {
            UUID org=guard.resources().projectOrg(corpus).orElseThrow(()->new IllegalStateException("STALE_SCOPE_GRANT"));
            if(!corpus.equals(project) || !org.equals(row.get("service_org_id")) || !"SYSTEM_INDEXING".equals(row.get("service_kind")))
                throw new IllegalStateException("STALE_SCOPE_GRANT");
        }
        indexService(corpus);
    }

    @Transactional
    public Delegation issue(UUID project) {
        var actor=OwnershipAttribution.currentOrDefault(mode.accessMode());
        var token=ExecutionTokenContext.current().orElse(null);
        authorize(actor,project,token);
        return new Delegation(actor,token);
    }

    @Transactional
    public void validate(UUID project,Map<String,Object> row) {
        try {
            var actor=actorFromGrant(row);
            var token=row.get("execution_token")==null?null:
                    RetrievalWire.read(row.get("execution_token").toString(),ExecutionToken.class);
            authorize(actor,project,token);
        } catch(AccessDeniedException | TaskOwnershipLostException refused) {
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        }
    }

    public ActorContext actorFromGrant(Map<String,Object> row) {
        if(row.get("actor_user_id")==null || row.get("actor_org_id")==null || row.get("actor_source")==null)
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        ActorSource source=ActorSource.valueOf((String)row.get("actor_source"));
        if(!source.isUser()) throw new IllegalStateException("STALE_SCOPE_GRANT");
        return new ActorContext((UUID)row.get("actor_user_id"),(UUID)row.get("actor_org_id"),source);
    }

    private void authorize(ActorContext actor,UUID project,ExecutionToken token) {
        if(token!=null) {
            writes.lockOwnedTask(token);
            var task=tasks.findById(token.taskId()).orElseThrow(()->new IllegalStateException("STALE_SCOPE_GRANT"));
            if(!task.actor().toActorContext().equals(actor)) throw new IllegalStateException("STALE_SCOPE_GRANT");
        }
        if(!actor.isUser() || actor.orgId()==null) throw new IllegalStateException("STALE_SCOPE_GRANT");
        var scopes=new ArrayList<>(List.of(AuthorizationScopes.org(actor.orgId()),AuthorizationScopes.user(actor.userId())));
        if(project!=null) scopes.add(AuthorizationScopes.project(project));
        guard.lockSharedFailClosed(scopes);
        if(project==null) guard.requireOrg(actor,actor.orgId(),null);
        else guard.requireProjectFor(actor,project,Capability.PROJECT_READ);
    }
}
