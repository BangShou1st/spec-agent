package com.specagent.access;

import com.specagent.access.identity.*;
import com.specagent.workspace.project.ProjectService;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OrganizationManagementIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired OrganizationManagementService management;
    @Autowired ProjectMemberManagementService projects;
    @Autowired ProjectService projectService;
    @Autowired AuthorizationGuard guard;
    @Autowired JdbcTemplate jdbc;
    private UUID org,owner,member;
    @BeforeEach void fixture() {
        owner=UUID.randomUUID();member=UUID.randomUUID();org=UUID.randomUUID();
        identities.insertUserIfAbsent(owner,"Owner");identities.insertUserIfAbsent(member,"Member");
        identities.insertOrganizationIfAbsent(org,"Governance","STANDARD");
        identities.upsertMembership(org,owner,OrgRole.OWNER);identities.upsertMembership(org,member,OrgRole.MEMBER);
    }
    @AfterEach void clean() {
        ActorContextHolder.clear();
        jdbc.update("DELETE FROM organization_members WHERE org_id=?",org);
        jdbc.update("DELETE FROM organizations WHERE id=?",org);
        jdbc.update("DELETE FROM app_users WHERE id IN (?,?)",owner,member);
    }
    @Test void ordinaryMemberCannotManageOrganizationOrPromoteSelf() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(member,org))) {
            assertThatThrownBy(()->management.members(org)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->management.change(org,member,OrgRole.OWNER)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(guard.resources().findMembership(org,member).orElseThrow().role()).isEqualTo(OrgRole.MEMBER);
    }
    @Test void ownerCanPromoteButCannotRemoveLastOwner() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(owner,org))) {
            management.change(org,member,OrgRole.ADMIN);
            assertThat(management.members(org)).hasSize(2);
            assertThatThrownBy(()->management.remove(org,owner)).isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("last active OWNER");
        }
    }
    @Test void administratorCannotPromoteSelfToOwner() {
        identities.upsertMembership(org,member,OrgRole.ADMIN);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(member,org))) {
            assertThatThrownBy(()->management.change(org,member,OrgRole.OWNER)).isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void administratorCannotDemoteAnOwnerEvenWhenAnotherOwnerRemains() {
        UUID secondOwner=UUID.randomUUID();
        identities.insertUserIfAbsent(secondOwner,"Second owner");
        identities.upsertMembership(org,secondOwner,OrgRole.OWNER);
        identities.upsertMembership(org,member,OrgRole.ADMIN);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(member,org))) {
            assertThatThrownBy(()->management.change(org,owner,OrgRole.MEMBER)).isInstanceOf(AccessDeniedException.class);
            assertThat(guard.resources().findMembership(org,owner).orElseThrow().role()).isEqualTo(OrgRole.OWNER);
        } finally {
            jdbc.update("DELETE FROM organization_members WHERE user_id=?",secondOwner);
            jdbc.update("DELETE FROM app_users WHERE id=?",secondOwner);
        }
    }
}
