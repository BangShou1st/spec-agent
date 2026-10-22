package com.specagent.retrieval;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.retrieval.config.*;
import com.specagent.retrieval.protocol.*;
import com.specagent.retrieval.workload.*;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

/** Committed fixtures + independent real PG transactions, not an enclosing test rollback. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RebuildLeaseIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired ProjectService projects;
    @Autowired ProjectDeletionService deletion;
    @Autowired NodeService nodes;
    @Autowired RetrievalIndexJobs jobs;
    @SpyBean RetrievalStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired AuthorizationGuard authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RebuildLeaseCoordinator leases;
    @Autowired RetrievalSourceJobs sourceJobs;
    @Autowired HelpCorpusJobs helpJobs;
    @Autowired OrganizationConfigurationScope configurationScope;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.boot.test.mock.mockito.MockBean EmbeddingTransport transport;
    record Fixture(ActorContext actor,UUID project,UUID id) {}
    private final List<Fixture> fixtures=new ArrayList<>();
    private Fixture fixture() {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"rebuild-lease-org","STANDARD");
        identities.insertUserIfAbsent(user,"rebuild-lease-owner");identities.upsertMembership(org,user,OrgRole.OWNER);
        ActorContext actor=ActorContext.oidc(user,org);UUID project,id;
        try(var scope=ActorContextHolder.bind(actor)) {
            var config=new EmbeddingSettings.Config("OPENAI_COMPATIBLE","http://127.0.0.1:18371/v1","lease-model-"+org,30,8,"raw-text.v1");
            settings.save(new EmbeddingSettings.Draft(config,"fixture-key",0L));
            var profile=settings.register(config,3,null,1);
            var p=projects.createProject("rebuild-lease-"+UUID.randomUUID());project=p.id();
            nodes.createRootNode(project,p.activeRouteId(),"rebuild vector source",null,List.of(),true);
            rebuilds.start(project,profile.profileId());
            id=jdbc.queryForObject("SELECT id FROM embedding_rebuilds WHERE corpus_id=?",UUID.class,project);
        }
        var f=new Fixture(actor,project,id);fixtures.add(f);return f;
    }
    private RebuildLeaseCoordinator secondWorker() {return new RebuildLeaseCoordinator(named,authorization,transactions,120,3,0);}
    private void expire(Fixture f) {jdbc.update("UPDATE embedding_rebuilds SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.id);}
    private IndexBatch batch(RebuildLeaseCoordinator.Token token) {
        try(var scope=RebuildLeaseContext.bind(token)) {return jobs.claim(token.corpus(),token.id()).orElseThrow();}
    }
    private IndexResult result(IndexBatch batch) {
        double[] values={1,0,0};var vector=new RetrievalWire.Vector(3,values,RetrievalVectors.checksum(values));
        var vectors=batch.sources().stream().map(s->new IndexVector(s.entryId(),s.sourceRef(),s.sourceVersion(),s.contentHash(),s.location(),vector)).toList();
        return new IndexResult(batch.protocolVersion(),batch.requestId(),batch.workload(),batch.scopeGrant(),batch.profileId(),batch.indexGeneration(),batch.deadline(),batch.jobId(),batch.leaseId(),batch.expectedVersion(),vectors);
    }
    @AfterEach void cleanup() {
        org.mockito.Mockito.reset(store);
        for(var f:fixtures) {
            jdbc.update("UPDATE organizations SET status='ACTIVE' WHERE id=?",f.actor.orgId());
            if(jdbc.queryForObject("SELECT count(*) FROM projects WHERE id=?",Integer.class,f.project)>0)
                try(var scope=ActorContextHolder.bind(f.actor)) {deletion.deleteProject(f.project);}
            jdbc.update("DELETE FROM embedding_service_settings WHERE org_id=?",f.actor.orgId());
            jdbc.update("DELETE FROM embedding_profile_registrations WHERE org_id=?",f.actor.orgId());
            jdbc.update("DELETE FROM embedding_service_revisions WHERE org_id=?",f.actor.orgId());
        }
    }
    @Test void independentWorkersAtomicallyClaimTheSameRebuildOnlyOnce() throws Exception {
        var f=fixture();var other=secondWorker();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return leases.claim(f.id);});
            var b=pool.submit(()->{start.await();return other.claim(f.id);});start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT execution_epoch FROM embedding_rebuilds WHERE id=?",Long.class,f.id)).isEqualTo(1L);
        }
    }
    @Test void startupRecoveryPreservesLiveLeaseAndQueuedIntent() {
        var active=fixture();var queued=fixture();leases.claim(active.id).orElseThrow();
        secondWorker().recoverExpired();
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id=?",String.class,active.id)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id=?",String.class,queued.id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT state FROM retrieval_index_generations WHERE id=?",String.class,active.id)).isEqualTo("PREPARING");
    }
    @Test void takeoverPreservesProgressAndRejectsOldFinishAndFailure() {
        var f=fixture();jdbc.update("UPDATE embedding_rebuilds SET total=7,processed=2 WHERE id=?",f.id);
        var old=leases.claim(f.id).orElseThrow();expire(f);var next=secondWorker().claim(f.id).orElseThrow();
        assertThat(next.epoch()).isEqualTo(old.epoch()+1);
        assertThatThrownBy(()->leases.finish(old,7,7,true)).isInstanceOf(IllegalStateException.class).hasMessage("STALE_WORKLOAD");
        assertThat(leases.fail(old,"EMBEDDING_TIMEOUT")).isFalse();
        var row=jdbc.queryForMap("SELECT state,total,processed FROM embedding_rebuilds WHERE id=?",f.id);
        assertThat(row.get("state")).isEqualTo("RUNNING");assertThat(row.get("total")).isEqualTo(7);assertThat(row.get("processed")).isEqualTo(2);
    }
    @Test void expiredParentLeaseFencesChildVectorsBeforeAnyEpochChange() {
        var f=fixture();var token=leases.claim(f.id).orElseThrow();var child=batch(token);expire(f);
        assertThatThrownBy(()->jobs.commit(result(child))).isInstanceOf(IllegalStateException.class).hasMessage("STALE_WORKLOAD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND pending_embedding IS NOT NULL",Integer.class,f.project)).isZero();
        assertThat(jdbc.queryForObject("SELECT execution_epoch FROM embedding_rebuilds WHERE id=?",Long.class,f.id)).isEqualTo(token.epoch());
    }
    @Test void successorInvalidatesOldGrantAndCompletesFromPreparedGeneration() {
        var f=fixture();var old=leases.claim(f.id).orElseThrow();var child=batch(old);expire(f);
        var next=secondWorker().claim(f.id).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT state FROM retrieval_index_jobs WHERE id=?",String.class,child.jobId())).isEqualTo("STALE");
        assertThatThrownBy(()->jobs.validateGrant(child)).isInstanceOf(IllegalStateException.class);
        var fresh=batch(next);assertThat(jobs.commit(result(fresh))).isTrue();assertThat(jobs.commit(result(fresh))).isFalse();
        secondWorker().finish(next,1,1,true);
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id=?",String.class,f.id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND pending_embedding IS NOT NULL",Integer.class,f.project)).isEqualTo(1);
    }
    @Test void disabledOrganizationStopsSuccessButAllowsNarrowMetadataFailure() {
        var f=fixture();var token=leases.claim(f.id).orElseThrow();jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",f.actor.orgId());
        assertThatThrownBy(()->leases.finish(token,1,1,true)).isInstanceOf(IllegalStateException.class);
        assertThat(leases.fail(token,"STALE_SCOPE_GRANT")).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id=?",String.class,f.id)).isEqualTo("FAILED");
    }
    @Test void expiredRecoveryRequeuesAndDoesNotReuseTheOldLease() {
        var f=fixture();var old=leases.claim(f.id).orElseThrow();expire(f);secondWorker().recoverExpired();
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_rebuilds WHERE id=?",String.class,f.id)).isEqualTo("QUEUED");
        var next=secondWorker().claim(f.id).orElseThrow();assertThat(next.lease()).isNotEqualTo(old.lease());
        assertThat(next.epoch()).isEqualTo(old.epoch()+1);
    }
    @Test void actualPythonProcessCompletesBoundedWorkerRebuildThroughParentBoundRpc() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_REBUILD_PYTHON_ACCEPTANCE")),"explicit Java/Python process qualification");
        var f=fixture();int brainPort;
        try(var socket=new java.net.ServerSocket(0)) {brainPort=socket.getLocalPort();}
        var executable=java.nio.file.Path.of(System.getenv().getOrDefault("SPEC_AGENT_GA_TEST_PYTHON","../agent-brain/.venv/Scripts/python.exe")).toAbsolutePath();
        assertThat(java.nio.file.Files.isRegularFile(executable)).isTrue();
        var builder=new ProcessBuilder(executable.toString(),"-m","uvicorn","spec_agent_brain.app:create_app","--factory","--host","127.0.0.1","--port",Integer.toString(brainPort));
        builder.directory(java.nio.file.Path.of("../agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE","broker");
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL","http://127.0.0.1:"+port+"/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET","dev-internal-secret");
        var log=java.nio.file.Files.createTempFile("p2-rebuild-python-",".log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());var python=builder.start();
        String origin="http://127.0.0.1:"+brainPort;
        try {
            boolean ready=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            try(var http=java.net.http.HttpClient.newHttpClient()) {
                while(System.nanoTime()<until && python.isAlive()) {
                    try {
                        ready=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/health")).timeout(java.time.Duration.ofSeconds(1)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()==200;
                        if(ready) break;
                    } catch(java.io.IOException unavailable) {}
                    Thread.sleep(50);
                }
            }
            assertThat(ready).as("isolated Python startup: %s",java.nio.file.Files.readString(log)).isTrue();
            org.mockito.Mockito.when(transport.remote(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                    .thenAnswer(call->((List<?>)call.getArgument(2)).stream().map(input->new double[]{1,0,0}).toList());
            com.specagent.common.BrainConnectionSettings connection=new com.specagent.common.BrainConnectionSettings() {
                public String getBaseUrl() {return origin;}
                public String getInternalSecret() {return "dev-internal-secret";}
            };
            var worker=new EmbeddingRebuilds(jdbc,settings,jobs,store,sourceJobs,helpJobs,connection,origin,true,authorization,configurationScope,leases);
            worker.recover();worker.tick();worker.tick();
            var row=jdbc.queryForMap("SELECT state,processed,total,execution_epoch FROM embedding_rebuilds WHERE id=?",f.id);
            assertThat(row.get("state")).as("worker outcome: %s; Python: %s",row,java.nio.file.Files.readString(log)).isEqualTo("READY");
            assertThat(row.get("processed")).isEqualTo(1);assertThat(row.get("total")).isEqualTo(1);
            assertThat(((Number)row.get("execution_epoch")).longValue()).isEqualTo(2);
            org.mockito.Mockito.verify(transport,org.mockito.Mockito.times(1)).remote(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("fixture-key"),org.mockito.ArgumentMatchers.anyList());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_scope_grants WHERE rebuild_id=? AND rebuild_epoch=1 AND rebuild_lease_id IS NOT NULL",Integer.class,f.id)).isEqualTo(1);
            assertThat(RebuildLeaseContext.current()).isNull();
        } finally {
            var children=python.descendants().toList();children.forEach(ProcessHandle::destroyForcibly);
            python.destroyForcibly();python.waitFor(5,TimeUnit.SECONDS);
            for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            for(int attempt=0;attempt<30;attempt++) {
                try {java.nio.file.Files.deleteIfExists(log);break;}
                catch(java.nio.file.FileSystemException locked) {if(attempt==29) throw locked;Thread.sleep(100);}
            }
        }
    }
    @Test void deletionWaitsOnAuthorizationBeforeCascadingSourceAndRebuildRows() throws Exception {
        var f=fixture();var token=leases.claim(f.id).orElseThrow();var child=batch(token);
        var inCommit=new CountDownLatch(1);var release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{inCommit.countDown();if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");return call.callRealMethod();})
                .when(store).canonicalCurrent(org.mockito.ArgumentMatchers.anyMap());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var committed=pool.submit(()->jobs.commit(result(child)));assertThat(inCommit.await(10,TimeUnit.SECONDS)).isTrue();
            var removed=pool.submit(()->{try(var scope=ActorContextHolder.bind(f.actor)) {deletion.deleteProject(f.project);}return true;});
            boolean locked=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try {
                while(System.nanoTime()<deadline) {
                    locked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0 AND query LIKE '%authorization_scopes%')",Boolean.class));
                    if(locked) break;Thread.sleep(25);
                }
            } finally {release.countDown();}
            assertThat(locked).isTrue();assertThat(committed.get(10,TimeUnit.SECONDS)).isTrue();assertThat(removed.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE id=?",Integer.class,f.project)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_rebuilds WHERE id=?",Integer.class,f.id)).isZero();
        }
    }
}
