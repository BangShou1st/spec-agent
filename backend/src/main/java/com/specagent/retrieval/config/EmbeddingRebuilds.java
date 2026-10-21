package com.specagent.retrieval.config;

import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization-owned project rebuilds; installation HELP requires explicit LOCAL authority. No automatic activation. */
@Service
public class EmbeddingRebuilds {
    private final JdbcTemplate jdbc;
    private final EmbeddingSettings settings;
    private final RetrievalIndexJobs jobs;
    private final RetrievalStore store;
    private final SharedRetrievalHost host;
    private final boolean enabled;
    private final com.specagent.access.AuthorizationGuard authorization;
    private final com.specagent.access.OrganizationConfigurationScope scope;
    private final com.specagent.retrieval.workload.RebuildLeaseCoordinator leases;
    private com.specagent.retrieval.workload.RebuildReadiness readiness;
    @org.springframework.beans.factory.annotation.Autowired
    public void setReadiness(com.specagent.retrieval.workload.RebuildReadiness readiness) { this.readiness=readiness; }
    public EmbeddingRebuilds(JdbcTemplate jdbc,EmbeddingSettings settings,RetrievalIndexJobs jobs,RetrievalStore store,
            RetrievalSourceJobs sources,HelpCorpusJobs help,BrainConnectionSettings connection,
            @Value("${spec.global-assistant.brain-base-url:http://127.0.0.1:8101}") String gaOrigin,
            @Value("${spec.embedding-settings.worker-enabled:true}") boolean enabled,
            com.specagent.access.AuthorizationGuard authorization,com.specagent.access.OrganizationConfigurationScope scope,
            com.specagent.retrieval.workload.RebuildLeaseCoordinator leases) {
        this.jdbc=jdbc; this.settings=settings; this.jobs=jobs; this.store=store; this.enabled=enabled;
        this.authorization=authorization;this.scope=scope;
        this.leases=leases;
        this.readiness=new com.specagent.retrieval.workload.RebuildReadiness(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        BrainConnectionSettings ga=new BrainConnectionSettings() {
            public String getBaseUrl() { return gaOrigin; }
            public String getInternalSecret() { return connection.getInternalSecret(); }
        };
        host=new SharedRetrievalHost(store,new PythonRetrievalClient(ga),jobs,sources,help);
    }
    public Map<String,Object> view() {
        UUID org=scope.readOrg();
        var actor=authorization.ownershipActor();
        var rebuild=jdbc.queryForList("""
                SELECT e.* FROM embedding_rebuilds e WHERE e.org_id=? AND
                    (? OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=e.corpus_id AND m.user_id=?))
                ORDER BY e.created_at DESC,e.id DESC LIMIT 1
                """,org,scope.installationDefaultsAllowed(),actor.userId());
        UUID corpus=rebuild.isEmpty()?(scope.installationDefaultsAllowed()?CuratedHelpSources.CORPUS:null):(UUID)rebuild.getFirst().get("corpus_id");
        var m=new LinkedHashMap<String,Object>(); m.put("corpusId",corpus); m.put("name",CuratedHelpSources.CORPUS.equals(corpus)?"全局助手产品帮助":"项目检索索引");
        var heads=jdbc.queryForList("SELECT * FROM retrieval_index_heads WHERE corpus_id=?",corpus);
        m.put("activeModel",heads.isEmpty()?null:store.model((String)heads.getFirst().get("profile_id")));
        m.put("activeDimensions",heads.isEmpty()?null:store.dimensions((String)heads.getFirst().get("profile_id")));
        m.put("activeGeneration",heads.isEmpty()?null:heads.getFirst().get("active_generation"));
        m.put("activeProfile",heads.isEmpty()?null:heads.getFirst().get("profile_id"));
        m.put("job",rebuild.isEmpty()?null:rebuild.getFirst());
        m.put("jobModel",rebuild.isEmpty()?null:store.model((String)rebuild.getFirst().get("profile_id")));
        m.put("readyEntries",jdbc.queryForObject("SELECT count(*) FROM retrieval_entries e JOIN retrieval_index_heads h ON h.corpus_id=e.corpus_id AND h.active_generation=e.index_generation AND h.profile_id=e.profile_id WHERE e.corpus_id=? AND e.retracted_at IS NULL AND e.embedding_status='READY'",Integer.class,corpus));
        return m;
    }
    @Transactional public Map<String,Object> start(UUID corpus,String profile) {
        UUID org=authorize(corpus);
        if(CuratedHelpSources.CORPUS.equals(corpus)) store.ensureHelpGeneration();
        else store.ensureProjectGeneration(corpus);
        jdbc.queryForList("SELECT * FROM retrieval_index_heads WHERE corpus_id=? FOR UPDATE",corpus);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embedding_rebuilds WHERE corpus_id=? AND state IN ('QUEUED','RUNNING','READY'))",Boolean.class,corpus))) throw new IllegalStateException("REBUILD_ALREADY_RUNNING");
        var approved=settings.profile(profile);
        if(!profile.equals(settings.view().get("candidateProfile"))) throw new IllegalStateException("EMBEDDING_TEST_EXPIRED");
        UUID generation=jobs.prepareGeneration(corpus,approved.profileId());
        long version=jdbc.queryForObject("SELECT version FROM retrieval_index_heads WHERE corpus_id=?",Long.class,corpus);
        jdbc.update("INSERT INTO embedding_rebuilds(id,org_id,corpus_id,profile_id,expected_head_version,state) VALUES(?,?,?,?,?,'QUEUED')",generation,org,corpus,profile,version);
        return view();
    }
    @Transactional public Map<String,Object> retry(UUID generation) {
        UUID org=authorizeGeneration(generation);
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND org_id=? AND state='FAILED' FOR UPDATE",generation,org);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embedding_rebuilds WHERE corpus_id=? AND state IN ('QUEUED','RUNNING','READY'))",Boolean.class,rows.getFirst().get("corpus_id")))) throw new IllegalStateException("REBUILD_ALREADY_RUNNING");
        jdbc.update("UPDATE retrieval_index_generations SET state='PREPARING' WHERE id=? AND state='FAILED'",generation);
        // Preserve a pre-upgrade FAILED fact before starting the next attempt.
        readiness.record(generation);
        jdbc.update("UPDATE embedding_rebuilds SET state='QUEUED',error_code=NULL,readiness_revision=readiness_revision+1 WHERE id=?",generation);
        return view();
    }
    @Transactional public Map<String,Object> activate(UUID generation) {
        UUID org=authorizeGeneration(generation);
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND org_id=? AND state='READY' FOR UPDATE",generation,org);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        var row=rows.getFirst(); jobs.activate((UUID)row.get("corpus_id"),generation,((Number)row.get("expected_head_version")).longValue());
        jdbc.update("UPDATE embedding_rebuilds SET state='ACTIVE' WHERE id=?",generation); return view();
    }
    /** A READY generation was never activated; abandoning it frees the head so a different profile can rebuild. */
    @Transactional public Map<String,Object> discard(UUID generation) {
        UUID org=authorizeGeneration(generation);
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND org_id=? AND state='READY' FOR UPDATE",generation,org);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        jdbc.update("UPDATE retrieval_entries SET pending_embedding=NULL,pending_profile_id=NULL,pending_generation=NULL WHERE corpus_id=? AND pending_generation=?",
                rows.getFirst().get("corpus_id"),generation);
        jdbc.update("UPDATE retrieval_index_generations SET state='DISCARDED' WHERE id=? AND state='PREPARING'",generation);
        jdbc.update("UPDATE embedding_rebuilds SET state='DISCARDED',error_code=NULL WHERE id=?",generation);
        readiness.record(generation);
        return view();
    }
    private UUID authorize(UUID corpus) {
        var actor=authorization.ownershipActor();
        var keys=new ArrayList<String>();
        if(actor.orgId()!=null) keys.add(com.specagent.access.AuthorizationScopes.org(actor.orgId()));
        if(actor.userId()!=null) keys.add(com.specagent.access.AuthorizationScopes.user(actor.userId()));
        if(!CuratedHelpSources.CORPUS.equals(corpus)) keys.add(com.specagent.access.AuthorizationScopes.project(corpus));
        authorization.lockSharedFailClosed(keys);
        UUID org=authorization.requireSelectedOrg(actor,com.specagent.access.Capability.CREDENTIAL_MANAGE);
        if(CuratedHelpSources.CORPUS.equals(corpus)) {
            if(!scope.installationDefaultsAllowed()) throw new IllegalArgumentException("UNSUPPORTED_CORPUS");
        } else authorization.requireProjectFor(actor,corpus,com.specagent.access.Capability.PROJECT_MANAGE);
        store.authorizeIndexCorpus(corpus);
        return org;
    }
    private UUID authorizeGeneration(UUID id) {
        UUID org=scope.readOrg();
        var actor=authorization.ownershipActor();
        var rows=jdbc.queryForList("""
                SELECT e.corpus_id FROM embedding_rebuilds e WHERE e.id=? AND e.org_id=? AND
                    (? OR EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=e.corpus_id AND m.user_id=?))
                """,id,org,scope.installationDefaultsAllowed(),actor.userId());
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        return authorize((UUID)rows.getFirst().get("corpus_id"));
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        if(!enabled) return;
        leases.recoverExpired();
    }
    @Scheduled(fixedDelayString="${spec.embedding-settings.worker-ms:1500}",initialDelay=5000)
    public void tick() {
        if(!enabled) return;
        var claimed=leases.claim();if(claimed.isEmpty()) return;
        var token=claimed.get();UUID id=token.id(),corpus=token.corpus();
        try(var binding=com.specagent.retrieval.workload.RebuildLeaseContext.bind(token)) {
            // Populate only missing shipped HELP projections, before preparing new vectors.
            store.authorizeIndexCorpus(corpus);
            if(CuratedHelpSources.CORPUS.equals(corpus)?host.splitOneHelp(store.ensureHelpGeneration()):host.splitOneSource(corpus,store.ensureProjectGeneration(corpus))) {
                leases.finish(token,null,null,false);return;
            }
            int total=jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND retracted_at IS NULL",Integer.class,corpus);
            if(total<1) throw new IllegalStateException("HELP_INDEX_NOT_READY");
            boolean worked=host.indexOneBatch(corpus,id);
            int processed=jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND retracted_at IS NULL AND pending_generation=? AND pending_profile_id=? AND pending_embedding IS NOT NULL",Integer.class,corpus,id,token.profile());
            if(!worked) {
                if(processed!=total) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
            }
            leases.finish(token,total,processed,!worked);
        } catch(RuntimeException ex) {
            leases.fail(token,ServiceSettingsErrors.safeCode(ex));
        }
    }
}
