package com.specagent.retrieval;

import com.specagent.assistant.config.SearchSettings;
import com.specagent.modelsettings.ModelCredentialCrypto;
import com.specagent.retrieval.config.*;
import com.specagent.retrieval.protocol.*;
import com.specagent.retrieval.protocol.RetrievalWire.Vector;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalServiceSettingsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelCredentialCrypto crypto;
    @Autowired EmbeddingSettings settings;
    @Autowired EmbeddingRebuilds rebuilds;
    @Autowired RetrievalStore store;
    @Autowired RetrievalIndexJobs jobs;
    @Autowired CuratedHelpSources help;
    @Autowired RetrievalSourceJobs sources;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM embedding_rebuilds");
        jdbc.update("DELETE FROM embedding_service_settings"); jdbc.update("DELETE FROM embedding_service_revisions");
        jdbc.update("DELETE FROM embedding_profiles"); jdbc.update("DELETE FROM embedding_probes"); jdbc.update("DELETE FROM search_settings");
    }
    EmbeddingSettings.Config config(String model) { return new EmbeddingSettings.Config("OPENAI_COMPATIBLE","http://127.0.0.1:18371/v1",model,30,8,"raw-text.v1"); }
    EmbeddingSettings.Profile approved(String model,int dim) {
        settings.save(new EmbeddingSettings.Draft(config(model),"fixture-embedding-secret",settings.revision()));
        return settings.register(config(model),dim,null,settings.revision());
    }
    @Test void searchIsEncryptedAndOmittedKeyPreservesExplicitClearNeverResurrectsEnvironment() {
        var search=new SearchSettings(jdbc,crypto,"fixture-environment-key");
        assertTrue(search.snapshot().configured()); assertEquals("ENVIRONMENT",search.view().get("source"));
        search.save(true,null,true,0L);
        String stored=jdbc.queryForObject("SELECT api_key FROM search_settings",String.class);
        assertTrue(stored.startsWith("enc:v1:")); assertFalse(stored.contains("fixture-environment-key"));
        assertFalse(write(search.view()).contains("fixture-environment-key"));
        assertEquals("fixture-environment-key",search.snapshot().key());
        search.save(false,null,false,1L); assertFalse(search.snapshot().configured());
        search.save(true,null,false,2L); assertEquals("fixture-environment-key",search.snapshot().key());
        assertThrows(IllegalStateException.class,()->search.clear(1L));
        search.clear(3L);
        var restarted=new SearchSettings(jdbc,crypto,"fixture-environment-key");
        assertFalse(restarted.snapshot().configured()); assertNull(restarted.snapshot().key());
        restarted.save(true,null,false,4L); assertFalse(restarted.snapshot().configured());
        restarted.save(true,"replacement-key",false,5L); assertEquals("replacement-key",restarted.snapshot().key());
        assertThrows(IllegalArgumentException.class,()->restarted.save(true,"••••key",false,6L));
    }
    @Test void profileIdentityExcludesCredentialAndConnectionLimitsButIncludesSameDimensionModelChanges() {
        var first=approved("model-a",3);
        var limits=new EmbeddingSettings.Config("OPENAI_COMPATIBLE",config("model-a").baseUrl(),"model-a",12,1,"raw-text.v1");
        settings.save(new EmbeddingSettings.Draft(limits,null,settings.revision()));
        var updated=settings.register(limits,3,null,settings.revision());
        assertEquals(first.profileId(),updated.profileId());
        assertEquals(12,updated.config().timeoutSeconds()); assertEquals(1,updated.config().batchSize());
        assertEquals(30,first.config().timeoutSeconds()); // Captured workloads remain immutable.
        settings.save(new EmbeddingSettings.Draft(config("model-a"),"replacement-embedding-key",settings.revision()));
        var changedKey=settings.register(config("model-a"),3,null,settings.revision());
        assertEquals(first.profileId(),changedKey.profileId());
        assertEquals("replacement-embedding-key",settings.key(first));
        var next=approved("model-b",3); assertNotEquals(first.profileId(),next.profileId());
        assertFalse(write(first).contains("fixture-embedding-secret"));
        assertTrue(jdbc.queryForObject("SELECT bool_and(api_key LIKE 'enc:v1:%') FROM embedding_service_revisions",Boolean.class));
        settings.clearCredential(settings.revision());
        assertThrows(IllegalStateException.class,()->settings.key(first));
        assertThrows(IllegalStateException.class,()->settings.key(next));
        assertNull(settings.view().get("maskedKey"));
        assertThrows(IllegalArgumentException.class,()->settings.validate(new EmbeddingSettings.Config("OLLAMA","http://arbitrary.example:11434","x",30,8,"raw-text.v1")));
    }
    @Test void helpSplittingSupportsApprovedDynamicProfilesWithoutChangingProjectDefaults() {
        var p=approved("help-model",3);
        UUID generation=jobs.prepareGeneration(CuratedHelpSources.CORPUS,p.profileId());
        var document=help.document("projects");
        var split=sources.createSplitJob(CuratedHelpSources.CORPUS,null,generation,"help:"+document.id(),document.hash(),"HELP",document.hash(),document.text()).orElseThrow();
        assertEquals("retrieval.v2",split.protocolVersion()); assertEquals(p.profileId(),split.profileId());
        validate(split);
    }
    @Test void testReceiptBindsExactConfigurationAndCredentialAndDoesNotPersistDraft() {
        var c=config("model-a"); long revision=settings.revision();
        UUID receipt=settings.probe(c,"draft-key",3,null);
        assertNull(settings.current()); assertEquals(revision,settings.revision());
        assertThrows(IllegalStateException.class,()->settings.save(new EmbeddingSettings.Draft(c,"different-key",revision,receipt)));
        assertEquals(revision,settings.revision());
        // A direct service invocation doesn't get Spring rollback between caught errors; isolate that failed write.
        jdbc.update("DELETE FROM embedding_service_settings"); jdbc.update("DELETE FROM embedding_service_revisions");
        settings.save(new EmbeddingSettings.Draft(c,"draft-key",0L,receipt));
        assertNotNull(settings.view().get("candidateProfile")); assertEquals(3,settings.view().get("dimensions"));
    }
    @Test void candidateVectorsStayPendingFailureRetainsOldIndexAndActivationFencesSourceAndHead() {
        UUID corpus=CuratedHelpSources.CORPUS;
        jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id=?",corpus);
        jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id=?",corpus);
        jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id=?",corpus);
        jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id=?",corpus);
        jdbc.update("DELETE FROM retrieval_entries WHERE corpus_id=?",corpus);
        UUID old=store.ensureHelpGeneration(); insertHelp(help.document("projects"),old,PROFILE,1024);
        var p=approved("model-a",3); UUID pending=jobs.prepareGeneration(corpus,p.profileId());
        var batch=jobs.claim(corpus,pending).orElseThrow(); assertEquals("retrieval.v2",batch.protocolVersion());
        assertEquals(p.profileId(),batch.profileId());
        var source=batch.sources().getFirst();
        var invalid=new IndexVector(source.entryId(),source.sourceRef(),source.sourceVersion(),source.contentHash(),source.location(),vector(4));
        var response=new IndexResult(batch.protocolVersion(),batch.requestId(),batch.workload(),batch.scopeGrant(),batch.profileId(),batch.indexGeneration(),batch.deadline(),batch.jobId(),batch.leaseId(),batch.expectedVersion(),List.of(invalid));
        assertThrows(IllegalArgumentException.class,()->jobs.commit(response));
        assertEquals(old,jdbc.queryForObject("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=?",UUID.class,corpus));
        var valid=new IndexVector(source.entryId(),source.sourceRef(),source.sourceVersion(),source.contentHash(),source.location(),vector(3));
        var result=new IndexResult(batch.protocolVersion(),batch.requestId(),batch.workload(),batch.scopeGrant(),batch.profileId(),batch.indexGeneration(),batch.deadline(),batch.jobId(),batch.leaseId(),batch.expectedVersion(),List.of(valid));
        assertTrue(jobs.commit(result)); assertFalse(jobs.commit(result));
        assertEquals(1024,jdbc.queryForObject("SELECT vector_dims(embedding) FROM retrieval_entries WHERE id=?",Integer.class,source.entryId()));
        assertEquals(3,jdbc.queryForObject("SELECT vector_dims(pending_embedding) FROM retrieval_entries WHERE id=?",Integer.class,source.entryId()));
        assertThrows(IllegalStateException.class,()->jobs.activate(corpus,pending,99));
        jobs.activate(corpus,pending,0);
        assertEquals(pending,jdbc.queryForObject("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=?",UUID.class,corpus));
        // A foreign vector dimension in the SAME table must never reach the distance operator.
        insertHelp(help.document("resources"),pending,approved("other-model",4).profileId(),4);
        var issued=store.issueHelp("项目",8,UUID.randomUUID());
        jdbc.update("UPDATE retrieval_scope_grants SET workload_kind='CONTEXT_PROJECTION' WHERE id=?",issued.scopeGrant().grantId());
        var workload=new Workload("CONTEXT_PROJECTION",issued.workload().id(),1);
        var request=new Candidates(issued.protocolVersion(),issued.requestId(),workload,issued.scopeGrant(),issued.profileId(),issued.indexGeneration(),issued.deadline(),issued.query(),List.of("vector"),8,vector(3),0.65);
        assertEquals(1,store.candidates(request).lanes().getFirst().entries().size());
        var wrong=new Candidates(issued.protocolVersion(),issued.requestId(),workload,issued.scopeGrant(),issued.profileId(),issued.indexGeneration(),issued.deadline(),issued.query(),List.of("vector"),8,vector(4),0.65);
        assertThrows(IllegalArgumentException.class,()->store.candidates(wrong));
    }
    private Vector vector(int dimensions) {
        double[] values=new double[dimensions]; values[0]=1;
        return new Vector(dimensions,values,RetrievalVectors.checksum(values));
    }
    private void insertHelp(CuratedHelpSources.Document document,UUID generation,String profile,int dimensions) {
        String ref="help:"+document.id()+":0"; var metadata=Map.of("helpDocId",document.id(),"rawContentHash",document.hash(),"rawSourceVersion",document.hash(),"chunkIndex",0,"startOffset",0,"endOffset",document.text().length(),"headings",List.of());
        jdbc.update("""
            INSERT INTO retrieval_entries(id,corpus_id,source_kind,source_id,source_ref,scope,authority,content,content_hash,metadata,
                embedding,embedding_dimensions,embedding_model,embedding_status,profile_id,index_generation)
            VALUES(?,?,'HELP_CHUNK',?,?,'HELP','EXTERNAL_EVIDENCE',?,?,CAST(? AS jsonb),CAST(? AS vector),?,'fixture-model','READY',?,?)
            """,UUID.nameUUIDFromBytes((corpusKey()+":"+ref).getBytes(StandardCharsets.UTF_8)),CuratedHelpSources.CORPUS,UUID.nameUUIDFromBytes(("help:"+document.id()).getBytes(StandardCharsets.UTF_8)),ref,document.text(),document.hash(),write(metadata),RetrievalStore.vectorLiteral(vector(dimensions).values()),dimensions,profile,generation);
    }
    private String corpusKey() { return CuratedHelpSources.CORPUS.toString(); }
}
