package com.specagent.retrieval.protocol;

import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable host job lease/CAS; Python only computes a batch already projected and granted here. */
@Service
public class RetrievalIndexJobs {
    private final NamedParameterJdbcTemplate jdbc;
    private final RetrievalStore store;
    public RetrievalIndexJobs(NamedParameterJdbcTemplate jdbc,RetrievalStore store) { this.jdbc=jdbc; this.store=store; }

    @Transactional public UUID prepareGeneration(UUID corpus) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO retrieval_index_generations(id,corpus_id,profile_id,state) VALUES(:id,:corpus,:profile,'PREPARING')",
                Map.of("id",id,"corpus",corpus,"profile",PROFILE));
        return id;
    }

    @Transactional public Optional<IndexBatch> claim(UUID corpus,UUID generation) {
        var gen=jdbc.queryForList("SELECT * FROM retrieval_index_generations WHERE id=:id AND corpus_id=:corpus AND profile_id=:profile AND state IN ('PREPARING','ACTIVE') FOR UPDATE",
                Map.of("id",generation,"corpus",corpus,"profile",PROFILE));
        if(gen.size()!=1) throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        // Expired derived computations may be re-claimed with a new job/lease; old results are fenced.
        jdbc.update("UPDATE retrieval_index_jobs SET state='STALE' WHERE corpus_id=:corpus AND state='CLAIMED' AND deadline<=clock_timestamp()",Map.of("corpus",corpus));
        // Exclude legacy oversized supplemental projections in bounded batches; canonical facts stay intact.
        jdbc.update("UPDATE retrieval_entries SET retracted_at=now(),embedding_status='UNAVAILABLE' WHERE id IN (SELECT id FROM retrieval_entries WHERE corpus_id=:corpus AND retracted_at IS NULL AND length(content)>12000 ORDER BY source_ref LIMIT 16 FOR UPDATE)",Map.of("corpus",corpus));
        var rows=jdbc.queryForList("""
                SELECT e.* FROM retrieval_entries e WHERE corpus_id=:corpus AND retracted_at IS NULL
                  AND length(content)>0 AND length(content)<=12000
                  AND NOT (COALESCE(e.index_generation=:generation AND e.profile_id=:profile AND e.embedding IS NOT NULL,FALSE)
                    OR COALESCE(e.pending_generation=:generation AND e.pending_profile_id=:profile AND e.pending_embedding IS NOT NULL,FALSE))
                  AND NOT EXISTS(SELECT 1 FROM retrieval_index_jobs j, jsonb_array_elements(j.request->'sources') s
                    WHERE j.corpus_id=:corpus AND j.state='CLAIMED' AND j.deadline>clock_timestamp() AND s->>'entryId'=e.id::text)
                ORDER BY e.source_ref LIMIT 16 FOR UPDATE OF e
                """,Map.of("corpus",corpus,"generation",generation,"profile",PROFILE));
        if(rows.isEmpty()) return Optional.empty();
        UUID project=(UUID)rows.getFirst().get("project_id");
        var scope=new RetrievalStore.Scope(corpus,project,null,project==null?Set.of("HELP"):Set.of("PROJECT","RESOURCE","ROUTE"),
                Set.of(),Set.of(),Set.of(),"",48,12,12000);
        List<IndexSource> sources=new ArrayList<>(); int chars=0;
        for(var row:rows) {
            if(row.get("content").toString().length()>12000 || !store.canonicalCurrent(row) || !new com.specagent.retrieval.index.RetrievalSourcePolicy().allowText(row.get("content").toString())) {
                // Retire only an invalid derived projection; canonical nodes/answers and frozen inputs remain untouched.
                jdbc.update("UPDATE retrieval_entries SET retracted_at=now(),embedding_status='UNAVAILABLE' WHERE id=:id",Map.of("id",row.get("id")));
                continue;
            }
            var source=store.source(row,scope);
            if(!new com.specagent.retrieval.index.RetrievalSourcePolicy().allowText(source.content())) continue;
            if(chars+source.content().length()>48000) break;
            chars+=source.content().length();
            sources.add(new IndexSource(source.entryId(),source.sourceRef(),source.sourceVersion(),source.contentHash(),source.content(),source.location()));
        }
        if(sources.isEmpty()) return Optional.empty();
        UUID job=UUID.randomUUID(),lease=UUID.randomUUID(),grant=UUID.randomUUID(),request=UUID.randomUUID();
        Instant deadline=Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(90);
        var batch=new IndexBatch("retrieval.v1",request,new Workload("INDEX_JOB",job,1),new Grant(grant,1,deadline),PROFILE,
                generation,deadline,job,lease,0,List.copyOf(sources));
        jdbc.update("""
                INSERT INTO retrieval_scope_grants(id,request_id,corpus_id,project_id,workload_kind,workload_id,epoch,profile_id,index_generation,deadline,scope)
                VALUES(:grant,:request,:corpus,:project,'INDEX_JOB',:job,1,:profile,:generation,:deadline,CAST(:scope AS jsonb))
                """,Maps.of("grant",grant,"request",request,"corpus",corpus,"project",project,"job",job,"profile",PROFILE,
                "generation",generation,"deadline",Timestamp.from(deadline),"scope",write(scope)));
        jdbc.update("""
                INSERT INTO retrieval_index_jobs(id,corpus_id,index_generation,profile_id,lease_id,state,deadline,request)
                VALUES(:job,:corpus,:generation,:profile,:lease,'CLAIMED',:deadline,CAST(:request AS jsonb))
                """,Map.of("job",job,"corpus",corpus,"generation",generation,"profile",PROFILE,"lease",lease,
                "deadline",Timestamp.from(deadline),"request",write(batch)));
        return Optional.of(batch);
    }

    @Transactional public boolean commit(IndexResult result) {
        validate(result);
        var generationRows=jdbc.queryForList("SELECT state FROM retrieval_index_generations WHERE id=:id AND profile_id=:profile AND state IN ('PREPARING','ACTIVE') FOR UPDATE",
                Map.of("id",result.indexGeneration(),"profile",result.profileId()));
        if(generationRows.size()!=1) throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        var rows=jdbc.queryForList("SELECT * FROM retrieval_index_jobs WHERE id=:id FOR UPDATE",Map.of("id",result.jobId()));
        if(rows.size()!=1) throw new IllegalStateException("STALE_WORKLOAD");
        var job=rows.getFirst(); var request=read(job.get("request").toString(),IndexBatch.class);
        if(!binding(request).equals(binding(result)) || !request.leaseId().equals(result.leaseId())
                || request.expectedVersion()!=result.expectedVersion() || result.vectors()==null || result.vectors().size()!=request.sources().size())
            throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        String hash=Hashes.sha256Hex(write(result));
        if("SUCCEEDED".equals(job.get("state"))) {
            if(!hash.equals(job.get("result_hash"))) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
            return false;
        }
        store.guard(request);
        Set<UUID> ids=new HashSet<>();
        for(var vector:result.vectors()) {
            vector.vector().validate();
            var source=request.sources().stream().filter(s->s.entryId().equals(vector.entryId())).findFirst().orElseThrow(()->new IllegalArgumentException("INVALID_VECTOR"));
            if(!ids.add(vector.entryId()) || !source.sourceRef().equals(vector.sourceRef()) || !source.sourceVersion().equals(vector.sourceVersion())
                    || !source.contentHash().equals(vector.contentHash()) || !source.location().equals(vector.location()))
                throw new IllegalArgumentException("INVALID_VECTOR");
            var current=jdbc.queryForList("SELECT * FROM retrieval_entries WHERE id=:id AND corpus_id=:corpus AND retracted_at IS NULL FOR UPDATE",
                    Map.of("id",vector.entryId(),"corpus",job.get("corpus_id")));
            if(current.size()!=1 || !store.canonicalCurrent(current.getFirst()) || !current.getFirst().get("source_version").toString().equals(vector.sourceVersion())
                    || !current.getFirst().get("content_hash").equals(vector.contentHash())
                    || !current.getFirst().get("content").equals(source.text())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        }
        boolean active="ACTIVE".equals(generationRows.getFirst().get("state"));
        for(var vector:result.vectors()) {
            var params=Map.of("id",vector.entryId(),"vector",RetrievalStore.vectorLiteral(vector.vector().values()),
                    "profile",result.profileId(),"generation",result.indexGeneration());
            jdbc.update(active?"""
                    UPDATE retrieval_entries SET embedding=CAST(:vector AS vector),profile_id=:profile,index_generation=:generation,
                        embedding_model='qwen3-embedding:0.6b',embedding_dimensions=1024,embedding_status='READY' WHERE id=:id
                    """:"""
                    UPDATE retrieval_entries SET pending_embedding=CAST(:vector AS vector),pending_profile_id=:profile,pending_generation=:generation WHERE id=:id
                    """,params);
        }
        if(jdbc.update("UPDATE retrieval_index_jobs SET state='SUCCEEDED',result_hash=:hash,version=version+1 WHERE id=:id AND state='CLAIMED' AND version=:version AND lease_id=:lease AND deadline>clock_timestamp()",
                Map.of("hash",hash,"id",result.jobId(),"version",result.expectedVersion(),"lease",result.leaseId()))!=1)
            throw new IllegalStateException("STALE_WORKLOAD");
        return true;
    }

    public IndexBatch validateGrant(IndexBatch request) {
        store.guard(request);
        var rows=jdbc.queryForList("SELECT request FROM retrieval_index_jobs WHERE id=:id AND lease_id=:lease AND version=:version AND state='CLAIMED'",
                Map.of("id",request.jobId(),"lease",request.leaseId(),"version",request.expectedVersion()));
        if(rows.size()!=1 || !write(read(rows.getFirst().get("request").toString(),IndexBatch.class)).equals(write(request)))
            throw new IllegalStateException("STALE_WORKLOAD");
        for(var source:request.sources()) {
            var current=jdbc.queryForList("SELECT * FROM retrieval_entries WHERE id=:id AND source_version=:version AND content_hash=:hash AND retracted_at IS NULL",
                Map.of("id",source.entryId(),"version",source.sourceVersion(),"hash",source.contentHash()));
            if(current.size()!=1 || !store.canonicalCurrent(current.getFirst())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        }
        return request;
    }

    @Transactional public void failed(UUID job,UUID lease) {
        jdbc.update("UPDATE retrieval_index_jobs SET state='FAILED' WHERE id=:id AND lease_id=:lease AND state='CLAIMED'",Map.of("id",job,"lease",lease));
    }

    /** One head CAS activates the fully prepared generation; never mixes profiles in a query. */
    @Transactional public void activate(UUID corpus,UUID generation,long expectedHeadVersion) {
        var heads=jdbc.queryForList("SELECT * FROM retrieval_index_heads WHERE corpus_id=:corpus FOR UPDATE",Map.of("corpus",corpus));
        if(heads.size()!=1 || ((Number)heads.getFirst().get("version")).longValue()!=expectedHeadVersion)
            throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        if(jdbc.queryForList("SELECT id FROM retrieval_index_generations WHERE id=:id AND corpus_id=:corpus AND state='PREPARING' AND profile_id=:profile FOR UPDATE",
                Map.of("id",generation,"corpus",corpus,"profile",PROFILE)).size()!=1) throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        if(jdbc.queryForObject("SELECT count(*) FROM retrieval_source_projections WHERE project_id=:corpus AND state='PENDING'",Map.of("corpus",corpus),Integer.class)!=0)
            throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        if(jdbc.queryForObject("SELECT count(*) FROM retrieval_entries WHERE corpus_id=:corpus AND retracted_at IS NULL AND NOT COALESCE(pending_generation=:id AND pending_profile_id=:profile AND pending_embedding IS NOT NULL,FALSE)",
                Map.of("corpus",corpus,"id",generation,"profile",PROFILE),Integer.class)!=0) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        jdbc.update("""
                UPDATE retrieval_entries SET embedding=pending_embedding,profile_id=pending_profile_id,index_generation=pending_generation,
                    embedding_model='qwen3-embedding:0.6b',embedding_dimensions=1024,embedding_status='READY',
                    pending_embedding=NULL,pending_profile_id=NULL,pending_generation=NULL WHERE corpus_id=:corpus AND pending_generation=:id
                """,Map.of("corpus",corpus,"id",generation));
        jdbc.update("UPDATE retrieval_index_generations SET state='RETIRED' WHERE id=:id",Map.of("id",heads.getFirst().get("active_generation")));
        jdbc.update("UPDATE retrieval_index_generations SET state='ACTIVE',version=version+1 WHERE id=:id",Map.of("id",generation));
        jdbc.update("UPDATE retrieval_index_heads SET active_generation=:id,profile_id=:profile,version=version+1 WHERE corpus_id=:corpus",
                Map.of("id",generation,"profile",PROFILE,"corpus",corpus));
    }
}
