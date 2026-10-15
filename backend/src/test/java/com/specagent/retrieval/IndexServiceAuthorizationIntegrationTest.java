package com.specagent.retrieval;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.retrieval.protocol.*;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IndexServiceAuthorizationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired RetrievalStore store;
    @Autowired RetrievalIndexJobs jobs;
    @Autowired HelpCorpusJobs help;
    @Autowired JdbcTemplate jdbc;
    record Fixture(UUID org,UUID project,UUID generation,IndexBatch batch) {}

    private Fixture fixture() {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"index-org","STANDARD");
        identities.insertUserIfAbsent(user,"index-owner");
        identities.upsertMembership(org,user,OrgRole.MEMBER);
        UUID project;
        try(var scope=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var p=projects.createProject("index-project-"+UUID.randomUUID());project=p.id();
            nodes.createRootNode(project,p.activeRouteId(),"private indexing content",null,List.of(),true);
        }
        UUID generation=store.ensureProjectGeneration(project);
        return new Fixture(org,project,generation,jobs.claim(project,generation).orElseThrow());
    }
    private IndexResult result(IndexBatch batch) {
        double[] values=new double[1024];values[0]=1;
        var vector=new RetrievalWire.Vector(1024,values,RetrievalVectors.checksum(values));
        var vectors=batch.sources().stream().map(s->new IndexVector(s.entryId(),s.sourceRef(),s.sourceVersion(),s.contentHash(),s.location(),vector)).toList();
        return new IndexResult(batch.protocolVersion(),batch.requestId(),batch.workload(),batch.scopeGrant(),batch.profileId(),
                batch.indexGeneration(),batch.deadline(),batch.jobId(),batch.leaseId(),batch.expectedVersion(),vectors);
    }
    @Test void backgroundIndexGrantHasNarrowPersistedOrganizationServiceScope() {
        var f=fixture();assertThat(ActorContextHolder.current()).isNull();
        var row=jdbc.queryForMap("SELECT service_kind,service_org_id,actor_user_id FROM retrieval_scope_grants WHERE id=?",f.batch.scopeGrant().grantId());
        assertThat(row.get("service_kind")).isEqualTo("SYSTEM_INDEXING");
        assertThat(row.get("service_org_id")).isEqualTo(f.org);
        assertThat(row.get("actor_user_id")).isNull();
        assertThat(jobs.validateGrant(f.batch)).isEqualTo(f.batch);
    }
    @Test void disabledOrganizationCannotClaimMoreWorkOrUseItsOldGrant() {
        var f=fixture();jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",f.org);
        assertThatThrownBy(()->jobs.claim(f.project,f.generation)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_SCOPE_GRANT");
        assertThatThrownBy(()->jobs.validateGrant(f.batch)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_SCOPE_GRANT");
    }
    @Test void organizationDisabledDuringComputeCannotCommitVectors() {
        var f=fixture();var result=result(f.batch);
        jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",f.org);
        assertThatThrownBy(()->jobs.commit(result)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_SCOPE_GRANT");
        assertThat(jdbc.queryForObject("SELECT state FROM retrieval_index_jobs WHERE id=?",String.class,f.batch.jobId())).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND embedding IS NOT NULL",Integer.class,f.project)).isZero();
    }
    @Test void grantCannotBorrowAnotherOrganizationsServiceIdentity() {
        var f=fixture();UUID other=UUID.randomUUID();identities.insertOrganizationIfAbsent(other,"other-index-org","STANDARD");
        assertThatThrownBy(()->jdbc.update("UPDATE retrieval_scope_grants SET service_org_id=? WHERE id=?",other,f.batch.scopeGrant().grantId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("retrieval_service_project_same_org");
    }
    @Test void nullServiceKindDoesNotBypassDatabaseCheck() {
        var f=fixture();
        assertThatThrownBy(()->jdbc.update("UPDATE retrieval_scope_grants SET service_kind=NULL WHERE id=?",f.batch.scopeGrant().grantId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("retrieval_index_service_required");
    }
    @Test void publicHelpUsesIndependentScopeWithoutPrivilegedLocalUser() {
        jdbc.update("DELETE FROM retrieval_entries WHERE corpus_id=?",CuratedHelpSources.CORPUS);
        jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id=?",CuratedHelpSources.CORPUS);
        var request=help.claim(store.ensureHelpGeneration()).orElseThrow();
        var row=jdbc.queryForMap("SELECT service_kind,service_org_id,actor_user_id FROM retrieval_scope_grants WHERE id=?",request.scopeGrant().grantId());
        assertThat(row.get("service_kind")).isEqualTo("SYSTEM_HELP_INDEXING");
        assertThat(row.get("service_org_id")).isNull();assertThat(row.get("actor_user_id")).isNull();
        assertThat(help.validateGrant(request)).isEqualTo(request);
    }
}
