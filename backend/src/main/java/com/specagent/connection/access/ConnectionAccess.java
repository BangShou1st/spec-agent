package com.specagent.connection.access;

import com.specagent.access.*;
import com.specagent.common.Maps;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Organization/user ownership is checked before candidate SQL and protected mutations. */
@Component
public class ConnectionAccess {
    private final AuthorizationGuard guard;
    private final NamedParameterJdbcTemplate jdbc;
    public ConnectionAccess(AuthorizationGuard guard,NamedParameterJdbcTemplate jdbc) {this.guard=guard;this.jdbc=jdbc;}
    public ActorContext actor() {
        var actor=guard.ownershipActor();
        if(!actor.isUser()) throw AccessDeniedException.forbidden(AccessReason.SERVICE_NOT_AUTHORIZED,"Connection access requires user delegation");
        guard.requireSelectedOrg(actor,null);return actor;
    }
    public Map<String,Object> parameters() {var actor=actor();return Maps.of("actorOrg",actor.orgId(),"actorUser",actor.userId());}
    public void lockForSourceRead() {var actor=actor();lock(actor);actor();}
    public String visible(String alias) {
        String prefix=alias==null || alias.isBlank()?"":alias+".";
        return prefix+"org_id=:actorOrg AND ("+prefix+"owner_kind='ORG' OR "+prefix+"owner_user_id=:actorUser)";
    }
    public UUID creationOrg(String ownerKind) {
        if(!Set.of("ORG","USER").contains(ownerKind)) throw new IllegalArgumentException("Invalid connection owner kind");
        var actor=actor();lock(actor);
        if(ownerKind.equals("ORG")) guard.requireSelectedOrg(actor,Capability.CREDENTIAL_MANAGE);
        else guard.requireSelectedOrg(actor,null);
        return actor.orgId();
    }
    public void manage(UUID id) {
        var actor=actor();lock(actor);
        var params=parameters();params.put("id",id);
        var rows=jdbc.queryForList("SELECT owner_kind FROM connections WHERE id=:id AND "+visible(""),params);
        if(rows.size()!=1) throw AccessDeniedException.notVisible("Connection not found");
        if("ORG".equals(rows.getFirst().get("owner_kind"))) guard.requireSelectedOrg(actor,Capability.CREDENTIAL_MANAGE);
    }
    @org.springframework.transaction.annotation.Transactional
    public void manage(UUID id,Runnable mutation) {manage(id);mutation.run();}
    private void lock(ActorContext actor) {
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(actor.orgId()),AuthorizationScopes.user(actor.userId())));
    }
}
