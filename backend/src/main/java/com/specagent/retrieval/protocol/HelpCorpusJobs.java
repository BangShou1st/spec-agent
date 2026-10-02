package com.specagent.retrieval.protocol;

import static com.specagent.retrieval.protocol.RetrievalWire.*;
import com.specagent.common.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product-help source management, using the shared split/index protocol and the same retrieval_entries table. */
@Service
public class HelpCorpusJobs {
    private final CuratedHelpSources help;
    private final RetrievalStore store;
    private final RetrievalSourceJobs sources;
    private final NamedParameterJdbcTemplate jdbc;
    public HelpCorpusJobs(CuratedHelpSources help,RetrievalStore store,RetrievalSourceJobs sources,NamedParameterJdbcTemplate jdbc) {
        this.help=help; this.store=store; this.sources=sources; this.jdbc=jdbc;
    }
    @Transactional public Optional<SplitRequest> claim(UUID generation) {
        if(jdbc.queryForList("SELECT id FROM retrieval_index_generations WHERE corpus_id=:corpus AND id=:generation AND state IN ('ACTIVE','PREPARING') FOR UPDATE",Map.of("corpus",CuratedHelpSources.CORPUS,"generation",generation)).size()!=1)
            throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        for(var document:help.documents()) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_entries WHERE corpus_id=:corpus AND source_ref=:ref AND metadata->>'rawSourceVersion'=:version AND retracted_at IS NULL)",Map.of("corpus",CuratedHelpSources.CORPUS,"ref","help:"+document.id()+":0","version",document.hash()),Boolean.class))) continue;
            var job=sources.createSplitJob(CuratedHelpSources.CORPUS,null,generation,"help:"+document.id(),document.hash(),"HELP_CHUNK",document.hash(),document.text());
            if(job.isPresent()) return job;
        }
        return Optional.empty();
    }
    public SplitRequest validateGrant(SplitRequest request) {
        var scope=store.guard(request);
        if(!CuratedHelpSources.CORPUS.equals(scope.corpusId()) || scope.projectId()!=null || !"HELP_CHUNK".equals(request.sourceKind())) throw new IllegalStateException("STALE_SCOPE_GRANT");
        var jobs=jdbc.queryForList("SELECT request FROM retrieval_index_jobs WHERE id=:id AND lease_id=:lease AND state='CLAIMED' AND version=:version",Map.of("id",request.jobId(),"lease",request.leaseId(),"version",request.expectedVersion()));
        if(jobs.size()!=1 || !write(read(jobs.getFirst().get("request").toString(),SplitRequest.class)).equals(write(request))) throw new IllegalStateException("STALE_WORKLOAD");
        current(request); return request;
    }
    private CuratedHelpSources.Document current(SplitRequest request) {
        if(!request.sourceRef().startsWith("help:")) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        var document=help.document(request.sourceRef().substring(5));
        if(!document.text().equals(request.text()) || !document.hash().equals(request.sourceVersion()) || !document.hash().equals(request.contentHash())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        return document;
    }
    @Transactional public boolean commit(SplitResult result) {
        var rows=jdbc.queryForList("SELECT * FROM retrieval_index_jobs WHERE id=:id FOR UPDATE",Map.of("id",result.jobId()));
        if(rows.size()!=1) throw new IllegalStateException("STALE_WORKLOAD");
        var row=rows.getFirst(); var request=read(row.get("request").toString(),SplitRequest.class);
        if(!binding(request).equals(binding(result)) || !request.leaseId().equals(result.leaseId()) || request.expectedVersion()!=result.expectedVersion()
                || !request.sourceRef().equals(result.sourceRef()) || !request.sourceVersion().equals(result.sourceVersion()) || !request.contentHash().equals(result.contentHash())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        String hash=Hashes.sha256Hex(write(result));
        if("SUCCEEDED".equals(row.get("state"))) { if(!hash.equals(row.get("result_hash"))) throw new IllegalStateException("SOURCE_VERSION_MISMATCH"); return false; }
        validateGrant(request); var document=current(request);
        RetrievalSourceJobs.verifySlices(request,result.chunks());
        jdbc.update("DELETE FROM retrieval_entries WHERE corpus_id=:corpus AND metadata->>'helpDocId'=:doc",Map.of("corpus",CuratedHelpSources.CORPUS,"doc",document.id()));
        for(var chunk:result.chunks()) {
            if(chunk.text().isBlank()) continue;
            String ref="help:"+document.id()+":"+chunk.index();
            var metadata=Map.of("helpDocId",document.id(),"rawContentHash",document.hash(),"rawSourceVersion",document.hash(),
                "chunkIndex",chunk.index(),"startOffset",chunk.startOffset(),"endOffset",chunk.endOffset(),"headings",chunk.headings());
            jdbc.update("""
                INSERT INTO retrieval_entries(id,corpus_id,source_kind,source_id,source_ref,scope,authority,content,content_hash,metadata,embedding_status)
                VALUES(:id,:corpus,'HELP_CHUNK',:source,:ref,'HELP','EXTERNAL_EVIDENCE',:text,:hash,CAST(:metadata AS jsonb),'PENDING')
                """,Map.of("id",UUID.nameUUIDFromBytes((CuratedHelpSources.CORPUS+":"+ref).getBytes(StandardCharsets.UTF_8)),"corpus",CuratedHelpSources.CORPUS,
                    "source",UUID.nameUUIDFromBytes(request.sourceRef().getBytes(StandardCharsets.UTF_8)),"ref",ref,"text",chunk.text(),"hash",chunk.contentHash(),"metadata",write(metadata)));
        }
        if(jdbc.update("UPDATE retrieval_index_jobs SET state='SUCCEEDED',result_hash=:hash,version=version+1 WHERE id=:id AND state='CLAIMED' AND lease_id=:lease AND version=:version AND deadline>clock_timestamp()",Map.of("hash",hash,"id",result.jobId(),"lease",result.leaseId(),"version",result.expectedVersion()))!=1) throw new IllegalStateException("STALE_WORKLOAD");
        return true;
    }
}
