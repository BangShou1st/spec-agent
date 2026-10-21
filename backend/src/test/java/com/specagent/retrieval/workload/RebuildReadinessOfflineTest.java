package com.specagent.retrieval.workload;

import com.specagent.access.*;
import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.config.*;
import com.specagent.retrieval.protocol.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real task creation/retry/worker terminal methods, mock JDBC. Never calls tick,
 * PythonRetrievalClient, Spring bootstrap, an embedding service, or a datasource. */
class RebuildReadinessOfflineTest {
    final UUID project=UUID.randomUUID(),org=UUID.randomUUID(),generation=UUID.randomUUID();
    final AuthorizationGuard guard=mock(AuthorizationGuard.class);
    final NamedParameterJdbcTemplate named=mock(NamedParameterJdbcTemplate.class);
    final LocalTransactions tx=new LocalTransactions();
    final RebuildReadiness readiness=new RebuildReadiness(named);
    RebuildLeaseCoordinator worker() {
        var resources=mock(AccessResourceRepository.class);when(guard.resources()).thenReturn(resources);
        when(resources.projectOrg(project)).thenReturn(Optional.of(org));
        when(guard.canUseOrg(any(),eq(org),isNull())).thenReturn(AccessDecision.allow());
        when(named.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("org_id",org,"project_id",project,
                "corpus_id",project,"profile_id","profile","id",generation)));
        var w=new RebuildLeaseCoordinator(named,guard,tx,120);w.setReadiness(readiness);return w;
    }
    RebuildLeaseCoordinator.Token token() {return new RebuildLeaseCoordinator.Token(generation,5,UUID.randomUUID(),UUID.randomUUID(),org,project,"profile");}
    @Test void actualWorkerReadyWritesDurableOutcomeInsideSameTransaction() {
        worker().finish(token(),12,12,true);
        var order=inOrder(named);order.verify(named).update(contains("SET state=:state"),argThat((Map<String,?> p)->p.get("state").equals("READY")));
        order.verify(named).update(contains("INSERT INTO embedding_rebuild_outcomes"),eq(Map.of("id",generation)));
        assertThat(tx.commits).isEqualTo(1);
    }
    @Test void intermediateBatchDoesNotCreateReadinessCompletion() {
        worker().finish(token(),12,4,false);
        verify(named,never()).update(contains("INSERT INTO embedding_rebuild_outcomes"),anyMap());
    }
    @Test void actualWorkerFailureWritesDurableFailureInSameTransaction() {
        assertThat(worker().fail(token(),"SOURCE_VERSION_MISMATCH")).isTrue();
        verify(named).update(contains("INSERT INTO embedding_rebuild_outcomes"),eq(Map.of("id",generation)));
        assertThat(tx.commits).isEqualTo(1);
    }
    @Test void eventPersistenceFailureRollsBackBusinessCompletion() {
        var w=worker();when(named.update(contains("INSERT INTO embedding_rebuild_outcomes"),anyMap())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(()->w.finish(token(),12,12,true)).hasMessage("storage unavailable");
        assertThat(tx.rollbacks).isEqualTo(1);assertThat(tx.commits).isZero();
    }
    @Test void failureOutcomePersistenceErrorIsNotSwallowedAsAStaleLease() {
        var w=worker();when(named.update(contains("INSERT INTO embedding_rebuild_outcomes"),anyMap())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(()->w.fail(token(),"SOURCE_VERSION_MISMATCH")).hasMessage("storage unavailable");
        assertThat(tx.rollbacks).isEqualTo(1);assertThat(tx.commits).isZero();
    }
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final EmbeddingSettings settings=mock(EmbeddingSettings.class);
    final RetrievalIndexJobs jobs=mock(RetrievalIndexJobs.class);
    final RetrievalStore store=mock(RetrievalStore.class);
    EmbeddingRebuilds management() {
        var scope=mock(OrganizationConfigurationScope.class);when(scope.readOrg()).thenReturn(org);
        when(guard.ownershipActor()).thenReturn(ActorContext.oidc(UUID.randomUUID(),org));
        when(guard.requireSelectedOrg(any(),eq(Capability.CREDENTIAL_MANAGE))).thenReturn(org);
        var service=spy(new EmbeddingRebuilds(jdbc,settings,jobs,store,mock(RetrievalSourceJobs.class),mock(HelpCorpusJobs.class),
                mock(BrainConnectionSettings.class),"http://127.0.0.1:8101",false,guard,scope,mock(RebuildLeaseCoordinator.class)));
        service.setReadiness(readiness);doReturn(Map.of("job",generation)).when(service).view();return service;
    }
    @Test void actualManagementCreationUsesPreparedGenerationAsDurableTaskIdentity() {
        var service=management();when(settings.profile("profile")).thenReturn(new EmbeddingSettings.Profile("profile",Map.of(),null,1,org));
        when(settings.view()).thenReturn(Map.of("candidateProfile","profile"));
        when(jobs.prepareGeneration(project,"profile")).thenReturn(generation);
        when(jdbc.queryForObject(contains("SELECT version"),eq(Long.class),eq(project))).thenReturn(4L);
        service.start(project,"profile");
        verify(jdbc).update(contains("INSERT INTO embedding_rebuilds"),eq(generation),eq(org),eq(project),eq("profile"),eq(4L));
    }
    void managementRow(String state) {
        when(jdbc.queryForList(contains("SELECT e.corpus_id"),eq(generation),eq(org),anyBoolean(),any())).thenReturn(List.of(Map.of("corpus_id",project)));
        when(jdbc.queryForList(contains("state='"+state+"' FOR UPDATE"),eq(generation),eq(org))).thenReturn(List.of(Map.of("corpus_id",project)));
    }
    @Test void explicitRetryPersistsOldOutcomeThenIncrementsReadinessRevisionNotLeaseEpoch() {
        var service=management();managementRow("FAILED");service.retry(generation);
        var order=inOrder(named,jdbc);order.verify(named).update(contains("INSERT INTO embedding_rebuild_outcomes"),eq(Map.of("id",generation)));
        order.verify(jdbc).update(contains("readiness_revision=readiness_revision+1"),eq(generation));
    }
    @Test void realDiscardProducesSafeDurableStateWithoutOverwritingEarlierReady() {
        var service=management();managementRow("READY");service.discard(generation);
        verify(named).update(contains("ON CONFLICT(generation_id,readiness_revision) DO NOTHING"),eq(Map.of("id",generation)));
    }
    static class LocalTransactions extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status) {rollbacks++;}
    }
}
