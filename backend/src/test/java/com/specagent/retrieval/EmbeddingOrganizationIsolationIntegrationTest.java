package com.specagent.retrieval;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.modelsettings.ModelCredentialCrypto;
import com.specagent.retrieval.config.EmbeddingSettings;
import com.specagent.retrieval.config.EmbeddingRebuilds;
import com.specagent.retrieval.protocol.CuratedHelpSources;
import com.specagent.workspace.project.ProjectService;
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
class EmbeddingOrganizationIsolationIntegrationTest {
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired IdentityRepository identities;
    @Autowired ModelCredentialCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired com.specagent.retrieval.protocol.RetrievalStore store;
    @Autowired com.specagent.retrieval.protocol.RetrievalIndexJobs jobs;
    @Autowired com.specagent.retrieval.config.EmbeddingBrokerController broker;
    @org.springframework.boot.test.mock.mockito.MockBean com.specagent.retrieval.config.EmbeddingTransport transport;
    private ActorContext actor(OrgRole role) {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"embedding-org","STANDARD");
        identities.insertUserIfAbsent(user,"embedding-user");identities.upsertMembership(org,user,role);
        return ActorContext.oidc(user,org);
    }
    private <T> T as(ActorContext actor,Supplier<T> action) {
        try(var ignored=ActorContextHolder.bind(actor)) {return action.get();}
    }
    private EmbeddingSettings.Config config() {
        return new EmbeddingSettings.Config("OPENAI_COMPATIBLE","http://127.0.0.1:18371/v1","same-model",30,8,"raw-text.v1");
    }
    private EmbeddingSettings.Profile configure(ActorContext actor,String key) {
        return as(actor,()->{
            settings.save(new EmbeddingSettings.Draft(config(),key,0L));
            return settings.register(config(),3,null,1);
        });
    }
    private com.specagent.retrieval.protocol.RetrievalWire.Search search(ActorContext actor,EmbeddingSettings.Profile profile) {
        UUID project=as(actor,()->projects.createProject("embedding-broker-"+UUID.randomUUID()).id());
        store.ensureProjectGeneration(project);
        UUID generation=jobs.prepareGeneration(project,profile.profileId());
        jobs.activate(project,generation,0);
        return as(actor,()->store.issueProject(new com.specagent.retrieval.RetrievalQuery(project,null,null,"EXPLICIT_SEARCH","private query",
                List.of(RetrievalScope.PROJECT),8,8000,Set.of(),Set.of()),UUID.randomUUID(),"CONTEXT_PROJECTION",Set.of()));
    }
    @Test void internalBrokerReconstructsEachGrantsOrganizationAndUsesItsOwnKey() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);
        var pa=configure(a,"organization-a-key");var pb=configure(b,"organization-b-key");
        var ra=search(a,pa);var rb=search(b,pb);
        org.mockito.Mockito.when(transport.remote(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call->List.of(new double[]{1,0,0}));
        assertThat(ActorContextHolder.current()).isNull();
        assertThat(broker.embeddings("dev-internal-secret",com.specagent.retrieval.protocol.RetrievalWire.write(ra)).getStatusCode().value()).isEqualTo(200);
        assertThat(broker.embeddings("dev-internal-secret",com.specagent.retrieval.protocol.RetrievalWire.write(rb)).getStatusCode().value()).isEqualTo(200);
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(transport,org.mockito.Mockito.times(2)).remote(org.mockito.ArgumentMatchers.any(),captured.capture(),org.mockito.ArgumentMatchers.anyList());
        assertThat(captured.getAllValues()).containsExactly("organization-a-key","organization-b-key");
    }
    @Test void organizationDisabledDuringProviderCallCannotReceiveLateVectors() {
        var a=actor(OrgRole.OWNER);var profile=configure(a,"organization-a-key");var request=search(a,profile);
        org.mockito.Mockito.when(transport.remote(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call->{jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",a.orgId());return List.of(new double[]{1,0,0});});
        var response=broker.embeddings("dev-internal-secret",com.specagent.retrieval.protocol.RetrievalWire.write(request));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?,?>)response.getBody()).containsKey("vectors")).isFalse();
    }
    @Test void newOrganizationHasNoImplicitLegacyOrOtherOrganizationConfiguration() {
        configure(actor(OrgRole.OWNER),"organization-a-key");var b=actor(OrgRole.OWNER);
        as(b,()->{assertThat(settings.current()).isNull();assertThat(settings.revision()).isZero();
            assertThat(settings.draftKey(new EmbeddingSettings.Draft(config(),null,0L))).isNull();
            assertThat(settings.view().get("source")).isEqualTo("NOT_CONFIGURED");return null;});
    }
    @Test void equalSemanticHashDoesNotShareApprovalsOrKeysAcrossOrganizations() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);
        var pa=configure(a,"organization-a-key");var pb=configure(b,"organization-b-key");
        assertThat(pa.profileId()).isEqualTo(pb.profileId());
        assertThat(pa.orgId()).isEqualTo(a.orgId());assertThat(pb.orgId()).isEqualTo(b.orgId());
        assertThat(settings.key(pa)).isEqualTo("organization-a-key");assertThat(settings.key(pb)).isEqualTo("organization-b-key");
    }
    @Test void profileWithoutOrganizationApprovalIsNotVisible() {
        var a=configure(actor(OrgRole.OWNER),"organization-a-key");var b=actor(OrgRole.OWNER);
        assertThatThrownBy(()->as(b,()->settings.profile(a.profileId()))).isInstanceOf(IllegalStateException.class).hasMessage("UNSUPPORTED_PROFILE");
    }
    @Test void clearingOneOrganizationsCredentialDoesNotRevokeAnotherOrganizationsKey() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);var pa=configure(a,"organization-a-key");var pb=configure(b,"organization-b-key");
        as(a,()->settings.clearCredential(1));
        assertThat(settings.key(pb)).isEqualTo("organization-b-key");
        assertThatThrownBy(()->settings.key(pa)).isInstanceOf(IllegalStateException.class).hasMessage("EMBEDDING_NOT_CONFIGURED");
    }
    @Test void ciphertextIsBoundToOrganizationTableAndRow() {
        var a=actor(OrgRole.OWNER);configure(a,"organization-a-key");var b=actor(OrgRole.OWNER);
        String cipher=jdbc.queryForObject("SELECT api_key FROM embedding_service_settings WHERE org_id=?",String.class,a.orgId());
        assertThat(cipher).startsWith("enc:v2:");
        assertThatThrownBy(()->crypto.decryptScoped(cipher,b.orgId(),"embedding_service_settings",1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->crypto.decryptScoped(cipher,a.orgId(),"embedding_service_revisions",1)).isInstanceOf(RuntimeException.class);
    }
    @Test void probeReceiptCannotBeConsumedInAnotherOrganization() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);
        UUID probe=as(a,()->settings.probe(config(),"key",3,null));
        assertThatThrownBy(()->as(b,()->settings.save(new EmbeddingSettings.Draft(config(),"key",0L,probe))))
                .isInstanceOf(IllegalStateException.class).hasMessage("EMBEDDING_TEST_EXPIRED");
    }
    @Test void organizationMemberCannotManageKeysOrRunCredentialProbes() {
        var member=actor(OrgRole.MEMBER);
        assertThatThrownBy(()->as(member,()->settings.save(new EmbeddingSettings.Draft(config(),"key",0L))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->as(member,()->settings.draftKey(new EmbeddingSettings.Draft(config(),"key",0L))))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void disabledOrganizationCannotSupplyPreviouslyApprovedKey() {
        var a=actor(OrgRole.OWNER);var profile=configure(a,"key");
        jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",a.orgId());
        assertThatThrownBy(()->settings.key(profile)).isInstanceOf(IllegalStateException.class).hasMessage("EMBEDDING_NOT_CONFIGURED");
    }
    @Test void oidcAdminCanRebuildOwnedProjectButCannotChangeGlobalPublicHelp() {
        var a=actor(OrgRole.OWNER);var profile=configure(a,"key");
        UUID project=as(a,()->projects.createProject("private-embedding-"+UUID.randomUUID()).id());
        as(a,()->rebuilds.start(project,profile.profileId()));
        assertThat(jdbc.queryForObject("SELECT org_id FROM embedding_rebuilds WHERE corpus_id=?",UUID.class,project)).isEqualTo(a.orgId());
        assertThatThrownBy(()->as(a,()->rebuilds.start(CuratedHelpSources.CORPUS,profile.profileId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("UNSUPPORTED_CORPUS");
    }
    @Test void rebuildMetadataCannotBorrowAnotherProjectsOrganization() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);
        var pa=configure(a,"a-key");var pb=configure(b,"b-key");
        assertThat(pa.profileId()).isEqualTo(pb.profileId());
        UUID project=as(a,()->projects.createProject("embedding-affinity-"+UUID.randomUUID()).id());
        as(a,()->rebuilds.start(project,pa.profileId()));
        assertThatThrownBy(()->jdbc.update("UPDATE embedding_rebuilds SET org_id=? WHERE corpus_id=?",b.orgId(),project))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("embedding_rebuild_project_same_org");
    }
    @Test void anotherOrganizationCannotReadOrRetryRebuildGeneration() {
        var a=actor(OrgRole.OWNER);var b=actor(OrgRole.OWNER);var pa=configure(a,"a-key");
        UUID project=as(a,()->projects.createProject("private-rebuild-"+UUID.randomUUID()).id());
        as(a,()->rebuilds.start(project,pa.profileId()));
        UUID id=jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id=?",UUID.class,project);
        as(b,()->{assertThat(rebuilds.view().get("job")).isNull();assertThat(rebuilds.view().get("corpusId")).isNull();return null;});
        assertThatThrownBy(()->as(b,()->rebuilds.retry(id))).isInstanceOf(IllegalStateException.class).hasMessage("REBUILD_NOT_READY");
    }
    @Test void sameOrganizationAdminWithoutProjectRoleCannotEnumerateOrRetryRebuild() {
        var a=actor(OrgRole.OWNER);var profile=configure(a,"a-key");
        UUID project=as(a,()->projects.createProject("hidden-rebuild-"+UUID.randomUUID()).id());
        as(a,()->rebuilds.start(project,profile.profileId()));
        UUID id=jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id=?",UUID.class,project);
        UUID user=UUID.randomUUID();identities.insertUserIfAbsent(user,"another-admin");
        identities.upsertMembership(a.orgId(),user,OrgRole.OWNER);var other=ActorContext.oidc(user,a.orgId());
        as(other,()->{assertThat(rebuilds.view().get("job")).isNull();return null;});
        assertThatThrownBy(()->as(other,()->rebuilds.retry(id)))
                .isInstanceOf(IllegalStateException.class).hasMessage("REBUILD_NOT_READY");
    }
}
