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

/** Explicit HELP-only migration. One bounded batch per tick; no project rebuild or automatic activation. */
@Service
public class EmbeddingRebuilds {
    private final JdbcTemplate jdbc;
    private final EmbeddingSettings settings;
    private final RetrievalIndexJobs jobs;
    private final RetrievalStore store;
    private final SharedRetrievalHost host;
    private final boolean enabled;
    public EmbeddingRebuilds(JdbcTemplate jdbc,EmbeddingSettings settings,RetrievalIndexJobs jobs,RetrievalStore store,
            RetrievalSourceJobs sources,HelpCorpusJobs help,BrainConnectionSettings connection,
            @Value("${spec.global-assistant.brain-base-url:http://127.0.0.1:8101}") String gaOrigin,
            @Value("${spec.embedding-settings.worker-enabled:true}") boolean enabled) {
        this.jdbc=jdbc; this.settings=settings; this.jobs=jobs; this.store=store; this.enabled=enabled;
        BrainConnectionSettings ga=new BrainConnectionSettings() {
            public String getBaseUrl() { return gaOrigin; }
            public String getInternalSecret() { return connection.getInternalSecret(); }
        };
        host=new SharedRetrievalHost(store,new PythonRetrievalClient(ga),jobs,sources,help);
    }
    public Map<String,Object> view() {
        var m=new LinkedHashMap<String,Object>(); m.put("corpusId",CuratedHelpSources.CORPUS); m.put("name","全局助手产品帮助");
        var heads=jdbc.queryForList("SELECT * FROM retrieval_index_heads WHERE corpus_id=?",CuratedHelpSources.CORPUS);
        m.put("activeModel",heads.isEmpty()?null:store.model((String)heads.getFirst().get("profile_id")));
        m.put("activeDimensions",heads.isEmpty()?null:store.dimensions((String)heads.getFirst().get("profile_id")));
        m.put("activeGeneration",heads.isEmpty()?null:heads.getFirst().get("active_generation"));
        m.put("activeProfile",heads.isEmpty()?null:heads.getFirst().get("profile_id"));
        var rebuild=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE corpus_id=? ORDER BY created_at DESC,id DESC LIMIT 1",CuratedHelpSources.CORPUS);
        m.put("job",rebuild.isEmpty()?null:rebuild.getFirst());
        m.put("jobModel",rebuild.isEmpty()?null:store.model((String)rebuild.getFirst().get("profile_id")));
        m.put("readyEntries",jdbc.queryForObject("SELECT count(*) FROM retrieval_entries e JOIN retrieval_index_heads h ON h.corpus_id=e.corpus_id AND h.active_generation=e.index_generation AND h.profile_id=e.profile_id WHERE e.corpus_id=? AND e.retracted_at IS NULL AND e.embedding_status='READY'",Integer.class,CuratedHelpSources.CORPUS));
        return m;
    }
    @Transactional public Map<String,Object> start(UUID corpus,String profile) {
        if(!CuratedHelpSources.CORPUS.equals(corpus)) throw new IllegalArgumentException("UNSUPPORTED_CORPUS");
        store.ensureHelpGeneration(); // transaction locks the HELP head creation/selection.
        jdbc.queryForList("SELECT * FROM retrieval_index_heads WHERE corpus_id=? FOR UPDATE",corpus);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embedding_rebuilds WHERE corpus_id=? AND state IN ('QUEUED','RUNNING','READY'))",Boolean.class,corpus))) throw new IllegalStateException("REBUILD_ALREADY_RUNNING");
        var approved=settings.profile(profile);
        if(!profile.equals(settings.view().get("candidateProfile"))) throw new IllegalStateException("EMBEDDING_TEST_EXPIRED");
        UUID generation=jobs.prepareGeneration(corpus,approved.profileId());
        long version=jdbc.queryForObject("SELECT version FROM retrieval_index_heads WHERE corpus_id=?",Long.class,corpus);
        jdbc.update("INSERT INTO embedding_rebuilds(id,corpus_id,profile_id,expected_head_version,state) VALUES(?,?,?,?,'QUEUED')",generation,corpus,profile,version);
        return view();
    }
    @Transactional public Map<String,Object> retry(UUID generation) {
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND state='FAILED' FOR UPDATE",generation);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embedding_rebuilds WHERE corpus_id=? AND state IN ('QUEUED','RUNNING','READY'))",Boolean.class,CuratedHelpSources.CORPUS))) throw new IllegalStateException("REBUILD_ALREADY_RUNNING");
        jdbc.update("UPDATE retrieval_index_generations SET state='PREPARING' WHERE id=? AND state='FAILED'",generation);
        jdbc.update("UPDATE embedding_rebuilds SET state='QUEUED',error_code=NULL WHERE id=?",generation);
        return view();
    }
    @Transactional public Map<String,Object> activate(UUID generation) {
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND state='READY' FOR UPDATE",generation);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        var row=rows.getFirst(); jobs.activate((UUID)row.get("corpus_id"),generation,((Number)row.get("expected_head_version")).longValue());
        jdbc.update("UPDATE embedding_rebuilds SET state='ACTIVE' WHERE id=?",generation); return view();
    }
    /** A READY generation was never activated; abandoning it frees the head so a different profile can rebuild. */
    @Transactional public Map<String,Object> discard(UUID generation) {
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=? AND state='READY' FOR UPDATE",generation);
        if(rows.size()!=1) throw new IllegalStateException("REBUILD_NOT_READY");
        jdbc.update("UPDATE retrieval_entries SET pending_embedding=NULL,pending_profile_id=NULL,pending_generation=NULL WHERE corpus_id=? AND pending_generation=?",
                CuratedHelpSources.CORPUS,generation);
        jdbc.update("UPDATE retrieval_index_generations SET state='DISCARDED' WHERE id=? AND state='PREPARING'",generation);
        jdbc.update("UPDATE embedding_rebuilds SET state='DISCARDED',error_code=NULL WHERE id=?",generation);
        return view();
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        if(!enabled) return;
        jdbc.update("UPDATE retrieval_index_generations SET state='FAILED' WHERE id IN (SELECT id FROM embedding_rebuilds WHERE state IN ('QUEUED','RUNNING'))");
        jdbc.update("UPDATE embedding_rebuilds SET state='FAILED',error_code='INTERRUPTED_BY_RESTART' WHERE state IN ('QUEUED','RUNNING')");
    }
    @Scheduled(fixedDelayString="${spec.embedding-settings.worker-ms:1500}",initialDelay=5000)
    public void tick() {
        if(!enabled) return;
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 1");
        if(rows.isEmpty()) return;
        var row=rows.getFirst(); UUID id=(UUID)row.get("id");
        try {
            jdbc.update("UPDATE embedding_rebuilds SET state='RUNNING' WHERE id=?",id);
            // Populate only missing shipped HELP projections, before preparing new vectors.
            if(host.splitOneHelp(store.ensureHelpGeneration())) return;
            int total=jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND retracted_at IS NULL",Integer.class,CuratedHelpSources.CORPUS);
            if(total<1) throw new IllegalStateException("HELP_INDEX_NOT_READY");
            boolean worked=host.indexOneBatch(CuratedHelpSources.CORPUS,id);
            int processed=jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=? AND retracted_at IS NULL AND pending_generation=? AND pending_profile_id=? AND pending_embedding IS NOT NULL",Integer.class,CuratedHelpSources.CORPUS,id,row.get("profile_id"));
            jdbc.update("UPDATE embedding_rebuilds SET total=?,processed=? WHERE id=?",total,processed,id);
            if(!worked) {
                if(processed!=total) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
                jdbc.update("UPDATE embedding_rebuilds SET state='READY' WHERE id=? AND state='RUNNING'",id);
            }
        } catch(RuntimeException ex) {
            jdbc.update("UPDATE embedding_rebuilds SET state='FAILED',error_code=? WHERE id=?",ServiceSettingsErrors.safeCode(ex),id);
            jdbc.update("UPDATE retrieval_index_generations SET state='FAILED' WHERE id=? AND state='PREPARING'",id);
        }
    }
}
