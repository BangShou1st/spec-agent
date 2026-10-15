package com.specagent.access.identity;

import com.specagent.access.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User-facing membership management; organization roles grant governance, never project content. */
@Service
public class OrganizationManagementService {
    public record Member(UUID userId,String displayName,String role,String status) {}
    public record Organization(UUID id,String name) {}
    private final AuthorizationGuard guard;
    private final IdentityRepository identities;
    private final IdentityService identity;
    private final NamedParameterJdbcTemplate jdbc;
    public OrganizationManagementService(AuthorizationGuard guard,IdentityRepository identities,
            IdentityService identity,NamedParameterJdbcTemplate jdbc) {
        this.guard=guard;this.identities=identities;this.identity=identity;this.jdbc=jdbc;
    }
    @Transactional
    public Organization create(String name) {
        var actor=guard.ownershipActor();
        if(!actor.isUser() || !guard.resources().findUser(actor.userId()).map(u->u.active()).orElse(false))
            throw AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,"An active user is required");
        if(name==null || name.isBlank() || name.trim().length()>120)
            throw new IllegalArgumentException("Organization name must contain 1 to 120 characters");
        UUID org=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,name.trim(),"STANDARD");
        identities.upsertMembership(org,actor.userId(),OrgRole.OWNER);
        guard.scopes().ensure(List.of(AuthorizationScopes.org(org),AuthorizationScopes.user(actor.userId())));
        guard.audit().recordQuietly(actor.userId(),org,"ORG_CREATE","ORGANIZATION",org.toString(),"ALLOW",null);
        return new Organization(org,name.trim());
    }
    public List<Member> members(UUID orgId) {
        guard.requireOrg(guard.ownershipActor(),orgId,Capability.ORG_MANAGE);
        return jdbc.query("""
                SELECT u.id,u.display_name,m.role,m.status FROM organization_members m
                JOIN app_users u ON u.id=m.user_id WHERE m.org_id=:org ORDER BY u.display_name,u.id
                """,java.util.Map.of("org",orgId),(rs,i)->new Member(rs.getObject("id",UUID.class),
                        rs.getString("display_name"),rs.getString("role"),rs.getString("status")));
    }
    @Transactional
    public void change(UUID orgId,UUID userId,OrgRole role) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId),AuthorizationScopes.user(userId)));
        var actor=guard.ownershipActor();
        guard.requireOrg(actor,orgId,Capability.ORG_MANAGE);
        var existing=guard.resources().findMembership(orgId,userId).orElse(null);
        if(role==OrgRole.OWNER || (existing!=null && existing.role()==OrgRole.OWNER))
            guard.requireOrg(actor,orgId,Capability.ORG_OWNER);
        identity.changeMembershipRole(orgId,userId,role);
        audit(actor,orgId,userId,"MEMBER_ROLE");
    }
    @Transactional
    public void add(UUID orgId,UUID userId,OrgRole role) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId),AuthorizationScopes.user(userId)));
        var actor=guard.ownershipActor();
        guard.requireOrg(actor,orgId,Capability.ORG_MANAGE);
        if(role==OrgRole.OWNER) guard.requireOrg(actor,orgId,Capability.ORG_OWNER);
        if(guard.resources().findMembership(orgId,userId).isPresent()) {
            change(orgId,userId,role);return;
        }
        if(!guard.resources().findUser(userId).map(u->u.active()).orElse(false))
            throw AccessDeniedException.notVisible("User not found");
        identity.addMember(orgId,userId,role);
        audit(actor,orgId,userId,"MEMBER_ADD");
    }
    @Transactional
    public void remove(UUID orgId,UUID userId) {
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(orgId),AuthorizationScopes.user(userId)));
        var actor=guard.ownershipActor();
        guard.requireOrg(actor,orgId,Capability.ORG_MANAGE);
        var target=guard.resources().findMembership(orgId,userId).orElse(null);
        if(target!=null && target.role()==OrgRole.OWNER) guard.requireOrg(actor,orgId,Capability.ORG_OWNER);
        identity.removeMembership(orgId,userId);
        audit(actor,orgId,userId,"MEMBER_REMOVE");
    }
    private void audit(ActorContext actor,UUID org,UUID user,String action) {
        guard.audit().recordQuietly(actor.userId(),org,action,"USER",user.toString(),"ALLOW",null);
    }
}
