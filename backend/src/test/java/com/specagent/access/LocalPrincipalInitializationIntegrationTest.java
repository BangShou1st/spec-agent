package com.specagent.access;

import com.specagent.access.identity.IdentityRepository;
import com.specagent.access.identity.IdentityService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalPrincipalInitializationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired AccessResourceRepository resources;
    @Autowired AuthorizationGuard guard;
    @Autowired JdbcTemplate jdbc;
    private IdentityService newInstance() {
        return new IdentityService(identities,resources,new IdentityModeProperties(),guard);
    }
    private void freshInstancesCannotUseOrg(String reason) {
        for(int i=0;i<2;i++) {
            var actor=newInstance().localActor();
            assertThat(guard.canUseOrg(actor,LocalPrincipalIds.ORG_ID,null).allowed()).isFalse();
            assertThat(guard.canUseOrg(actor,LocalPrincipalIds.ORG_ID,null).reasonCode()).isEqualTo(reason);
        }
    }
    @Test void disabledMembershipRemainsDisabledAcrossNewIdentityInstances() {
        identities.updateMembershipStatus(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID,"DISABLED");
        freshInstancesCannotUseOrg(AccessReason.MEMBERSHIP_DISABLED);
        assertThat(resources.findMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID).orElseThrow().active()).isFalse();
    }
    @Test void removedMembershipIsNotRecreated() {
        jdbc.update("DELETE FROM organization_members WHERE org_id=? AND user_id=?",LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID);
        freshInstancesCannotUseOrg(AccessReason.NOT_ORG_MEMBER);
        assertThat(resources.findMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID)).isEmpty();
    }
    @Test void downgradedRoleIsNotOverwrittenWithOwner() {
        identities.upsertMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID,OrgRole.MEMBER);
        for(int i=0;i<2;i++) {
            var actor=newInstance().localActor();
            assertThat(guard.canUseOrg(actor,LocalPrincipalIds.ORG_ID,Capability.ORG_MANAGE).reasonCode())
                    .isEqualTo(AccessReason.ORG_CAPABILITY_MISSING);
        }
        assertThat(resources.findMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID).orElseThrow().role()).isEqualTo(OrgRole.MEMBER);
    }
    @Test void disabledUserIsNotReactivated() {
        identities.updateUserStatus(LocalPrincipalIds.USER_ID,"DISABLED");
        freshInstancesCannotUseOrg(AccessReason.USER_DISABLED);
        assertThat(resources.findUser(LocalPrincipalIds.USER_ID).orElseThrow().active()).isFalse();
    }
    @Test void disabledOrganizationIsNotReactivated() {
        jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",LocalPrincipalIds.ORG_ID);
        freshInstancesCannotUseOrg(AccessReason.ORG_DISABLED);
        assertThat(resources.findOrganization(LocalPrincipalIds.ORG_ID).orElseThrow().active()).isFalse();
    }
    @Test void migratedActiveLocalIdentityContinuesToWork() {
        var actor=newInstance().localActor();
        assertThat(actor.userId()).isEqualTo(LocalPrincipalIds.USER_ID);
        assertThat(actor.orgId()).isEqualTo(LocalPrincipalIds.ORG_ID);
        assertThat(guard.canUseOrg(actor,actor.orgId(),Capability.ORG_MANAGE).allowed()).isTrue();
    }
    @Test void oidcModeDoesNotIssueLocalIdentityOrChangeMembership() {
        var mode=new IdentityModeProperties();mode.setMode("OIDC");
        var service=new IdentityService(identities,resources,mode,guard);
        var before=resources.findMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID);
        assertThatThrownBy(service::localActor).isInstanceOf(AccessDeniedException.class);
        assertThat(resources.findMembership(LocalPrincipalIds.ORG_ID,LocalPrincipalIds.USER_ID)).isEqualTo(before);
    }
}
