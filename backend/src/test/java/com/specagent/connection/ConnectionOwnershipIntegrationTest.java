package com.specagent.connection;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.connection.credentials.SecretStore;
import java.util.*;
import java.util.function.Supplier;
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
class ConnectionOwnershipIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ConnectionLifecycleService lifecycle;
    @Autowired ConnectionRepository repository;
    @Autowired SecretStore secrets;
    @Autowired ConnectionMcpConnectionLookup lookup;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.specagent.mcp.McpDiscoveryCacheRepository cache;
    private UUID org() {UUID org=UUID.randomUUID();identities.insertOrganizationIfAbsent(org,"connection-org","STANDARD");return org;}
    private ActorContext actor(UUID org,OrgRole role) {
        UUID user=UUID.randomUUID();identities.insertUserIfAbsent(user,"connection-user");identities.upsertMembership(org,user,role);
        return ActorContext.oidc(user,org);
    }
    private <T> T as(ActorContext actor,Supplier<T> action) {try(var scope=ActorContextHolder.bind(actor)){return action.get();}}
    private Connection create(ActorContext actor,String owner,String name,String secret) {
        return as(actor,()->lifecycle.createOwned(ConnectionKind.CUSTOM_MCP,name,Map.of("serverUrl","https://mcp.example.test/service"),secret,owner));
    }
    @Test void visibleResponseProjectsOwnershipWithoutExposingInternalOwnerOrCredentialReference() throws Exception {
        UUID organization = org();
        var member = actor(organization, OrgRole.MEMBER);
        var admin = actor(organization, OrgRole.OWNER);
        var personal = create(member, "USER", "personal-response", "private-secret");
        var shared = create(admin, "ORG", "shared-response", "shared-secret");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        as(member, () -> {
            assertThat(repository.list()).extracting(Connection::id).containsExactlyInAnyOrder(personal.id(), shared.id());
            return null;
        });
        for (var connection : List.of(personal, shared)) {
            var listJson = mapper.readTree(mapper.writeValueAsString(ConnectionController.ConnectionResponse.from(connection, "****cret")));
            var detailJson = mapper.readTree(mapper.writeValueAsString(ConnectionController.ConnectionDetailResponse.from(connection, "****cret")));
            for (var json : List.of(listJson, detailJson)) {
                assertThat(json.get("ownerKind").asText()).isEqualTo(connection.ownerKind());
                assertThat(json.has("ownerUserId")).isFalse();
                assertThat(json.has("orgId")).isFalse();
                assertThat(json.has("credentialRef")).isFalse();
                assertThat(json.toString()).doesNotContain("private-secret", "shared-secret");
            }
        }
    }

    @Test void scopedCandidateQueriesCannotEnumerateOtherOrganizationsConnections() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var ca=create(a,"ORG","same-name",null);var cb=create(b,"ORG","same-name",null);
        as(a,()->{
            assertThat(repository.list()).extracting(Connection::id).containsExactly(ca.id());
            assertThat(repository.findById(cb.id())).isEmpty();assertThat(repository.findById(cb.connectionId())).isEmpty();
            assertThat(lookup.findByRowId(cb.id())).isEmpty();
            assertThat(lookup.list()).extracting(com.specagent.mcp.runtime.McpConnectionTarget::rowId).containsExactly(ca.id());return null;
        });
    }
    @Test void personalConnectionAndCredentialAreInvisibleEvenToSameOrgAdmin() {
        UUID org=org();var member=actor(org,OrgRole.MEMBER);var admin=actor(org,OrgRole.OWNER);
        var own=create(member,"USER","personal","personal-credential");
        assertThat(own.ownerUserId()).isEqualTo(member.userId());assertThat(own.orgId()).isEqualTo(org);
        as(admin,()->{assertThat(repository.list()).isEmpty();assertThat(secrets.maskedSuffix(own.credentialRef())).isNull();return null;});
        as(member,()->{assertThat(secrets.resolve(own.credentialRef())).isEqualTo("personal-credential");lifecycle.disable(own.id());return null;});
    }
    @Test void ordinaryMemberCannotCreateOrManageSharedOrganizationCredential() {
        UUID org=org();var admin=actor(org,OrgRole.OWNER);var member=actor(org,OrgRole.MEMBER);
        var shared=create(admin,"ORG","shared","organization-credential");
        assertThatThrownBy(()->create(member,"ORG","forbidden","x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->as(member,()->{lifecycle.disable(shared.id());return null;})).isInstanceOf(AccessDeniedException.class);
    }
    @Test void credentialsCannotBeReadOrAssignedAcrossOrganizations() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var ca=create(a,"ORG","a","credential-a");var cb=create(b,"ORG","b","credential-b");
        as(b,()->{assertThat(secrets.maskedSuffix(ca.credentialRef())).isNull();
            assertThatThrownBy(()->secrets.resolve(ca.credentialRef())).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);return null;});
        assertThatThrownBy(()->as(b,()->{repository.updateCredentialRef(cb.id(),ca.credentialRef());return null;}))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("selected_credential_same_connection");
    }
    @Test void deletingCredentialPreservesBearerIntentAndHidesAgentCapability() {
        var a=actor(org(),OrgRole.OWNER);var c=create(a,"ORG","required","credential");
        as(a,()->{repository.updateStatus(c.id(),ConnectionStatus.CONNECTED,null);repository.updateEnabled(c.id(),true);
            secrets.delete(c.credentialRef());var current=repository.findById(c.id()).orElseThrow();
            assertThat(current.credentialRef()).isNull();assertThat(current.authKind()).isEqualTo("BEARER");
            assertThat(current.agentVisible()).isFalse();assertThat(ConnectionMcpConnectionLookup.toTarget(current).authKind()).isEqualTo("BEARER");return null;});
    }
    @Test void disabledMemberCannotListOrResolvePreviouslyVisibleCredentials() {
        var a=actor(org(),OrgRole.OWNER);var c=create(a,"USER","own","credential");
        identities.updateMembershipStatus(a.orgId(),a.userId(),"DISABLED");
        assertThatThrownBy(()->as(a,repository::list)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->as(a,()->secrets.resolve(c.credentialRef()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void enabledNamesCanRepeatAcrossOrganizations() {
        var a=actor(org(),OrgRole.OWNER);var b=actor(org(),OrgRole.OWNER);
        var ca=create(a,"ORG","same-enabled-name",null);var cb=create(b,"ORG","same-enabled-name",null);
        for(var entry:List.of(Map.entry(a,ca),Map.entry(b,cb))) as(entry.getKey(),()->{
            repository.updateStatus(entry.getValue().id(),ConnectionStatus.TESTED,null);lifecycle.enable(entry.getValue().id());return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM connections WHERE name='same-enabled-name' AND enabled",Integer.class)).isEqualTo(2);
    }

    @Test void credentialForeignKeyUnbindingInvalidatesCachedDiscovery() {
        var a=actor(org(),OrgRole.OWNER);var c=create(a,"USER","cached","credential");
        as(a,()->{
            assertThat(cache.upsert(c.id(),c.configurationVersion(),List.of(),List.of(),List.of(),"old")).isTrue();
            secrets.delete(c.credentialRef());
            var updated=repository.findById(c.id()).orElseThrow();
            assertThat(updated.configurationVersion()).isGreaterThan(c.configurationVersion());
            assertThat(cache.findByConnection(c.id(),updated.configurationVersion())).isEmpty();
            assertThat(cache.upsert(c.id(),c.configurationVersion(),List.of(),List.of(),List.of(),"late")).isFalse();
            return null;
        });
    }

    @Test void disableAndReenableCannotReviveOldConnectionSnapshot() {
        var a=actor(org(),OrgRole.OWNER);var c=create(a,"USER","toggle",null);
        as(a,()->{
            repository.updateEnabled(c.id(),true);
            var before=repository.findById(c.id()).orElseThrow();
            repository.updateEnabled(c.id(),false);repository.updateEnabled(c.id(),true);
            var after=repository.findById(c.id()).orElseThrow();
            assertThat(after.configurationVersion()).isGreaterThan(before.configurationVersion());
            assertThat(cache.upsert(c.id(),before.configurationVersion(),List.of(),List.of(),List.of(),"late")).isFalse();
            return null;
        });
    }

    @Test void oldNetworkFailureCannotOverwriteNewConfigurationState() {
        var a=actor(org(),OrgRole.OWNER);var c=create(a,"USER","late-failure",null);
        as(a,()->{
            repository.updateEnabled(c.id(),true);
            assertThat(repository.updateStatusIfCurrent(c.id(),c.configurationVersion(),ConnectionStatus.FAILED,"late failure")).isFalse();
            assertThat(repository.findById(c.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.CREATED);
            return null;
        });
    }
}
