package com.specagent.access.identity;

import com.specagent.access.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectMemberManagementService {
    public record Member(UUID userId,String displayName,String role) {}
    private final AuthorizationGuard guard;
    private final AuthorizationRevocationOutbox outbox;
    private final NamedParameterJdbcTemplate jdbc;
    public ProjectMemberManagementService(AuthorizationGuard guard,AuthorizationRevocationOutbox outbox,
            NamedParameterJdbcTemplate jdbc) {this.guard=guard;this.outbox=outbox;this.jdbc=jdbc;}
    public List<Member> members(UUID project) {
        guard.requireProjectGovernance(project);
        return jdbc.query("""
                SELECT u.id,u.display_name,m.role FROM project_members m JOIN app_users u ON u.id=m.user_id
                WHERE m.project_id=:id ORDER BY u.display_name,u.id
                """,Map.of("id",project),(rs,i)->new Member(rs.getObject("id",UUID.class),
                        rs.getString("display_name"),rs.getString("role")));
    }
    @Transactional
    public void grant(UUID project,UUID user,ProjectRole role) {
        UUID org=org(project);
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(org),AuthorizationScopes.project(project),
                AuthorizationScopes.user(user)));
        guard.requireProjectGovernance(project);
        var member=guard.resources().findMembership(org,user).orElse(null);
        if(member==null || !member.active() || !guard.resources().findUser(user).map(u->u.active()).orElse(false))
            throw AccessDeniedException.notVisible("Organization member not found");
        jdbc.update("""
                INSERT INTO project_members(project_id,user_id,org_id,role) VALUES(:project,:user,:org,:role)
                ON CONFLICT(project_id,user_id) DO UPDATE SET role=EXCLUDED.role
                """,Map.of("project",project,"user",user,"org",org,"role",role.name()));
        changed(project,user,org,"PROJECT_ROLE");
    }
    @Transactional
    public void remove(UUID project,UUID user) {
        UUID org=org(project);
        guard.lockExclusiveFailClosed(List.of(AuthorizationScopes.org(org),AuthorizationScopes.project(project),
                AuthorizationScopes.user(user)));
        guard.requireProjectGovernance(project);
        jdbc.update("DELETE FROM project_members WHERE project_id=:p AND user_id=:u",Map.of("p",project,"u",user));
        changed(project,user,org,"PROJECT_MEMBER_REMOVE");
    }
    public List<String> capabilities(UUID project) {
        var actor=guard.ownershipActor();
        guard.requireProjectFor(actor,project,Capability.PROJECT_READ);
        return List.of(Capability.PROJECT_READ,Capability.PROJECT_WRITE,Capability.PROJECT_MANAGE,Capability.CREDENTIAL_USE)
                .stream().filter(c->guard.projectDecisionFor(actor,project,c).allowed()).map(Enum::name).toList();
    }
    private UUID org(UUID project) {
        return guard.resources().projectOrg(project).orElseThrow(()->AccessDeniedException.notVisible("Project not found"));
    }
    private void changed(UUID project,UUID user,UUID org,String action) {
        guard.scopes().bumpVersions(List.of(AuthorizationScopes.project(project),AuthorizationScopes.user(user)));
        outbox.append(user,org);
        guard.audit().recordQuietly(guard.ownershipActor().userId(),org,action,"PROJECT",project.toString(),"ALLOW",null);
    }
}
