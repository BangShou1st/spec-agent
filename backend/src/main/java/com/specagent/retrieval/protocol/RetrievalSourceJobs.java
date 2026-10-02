package com.specagent.retrieval.protocol;

import static com.specagent.retrieval.protocol.RetrievalWire.*;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.retrieval.*;
import com.specagent.retrieval.persistence.*;
import com.specagent.retrieval.index.RetrievalSourcePolicy;
import com.specagent.workspace.node.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized raw snapshots and leased derived work. No splitting or network inside host transactions. */
@Service
public class RetrievalSourceJobs implements com.specagent.retrieval.index.ResourceProjectionJobs {
    private final NamedParameterJdbcTemplate jdbc;
    private final RetrievalStore store;
    private final RetrievalEntryRepository entries;
    private final NodeRepository nodes;
    public RetrievalSourceJobs(NamedParameterJdbcTemplate jdbc,RetrievalStore store,RetrievalEntryRepository entries,NodeRepository nodes) {
        this.jdbc=jdbc; this.store=store; this.entries=entries; this.nodes=nodes;
    }
    @Transactional public void invalidate(UUID node) {
        jdbc.update("DELETE FROM retrieval_source_projections WHERE node_id=:id",Map.of("id",node));
    }
    public List<UUID> resourceBackfillProjects(int limit) {
        return jdbc.queryForList("SELECT DISTINCT n.project_id FROM nodes n WHERE n.kind='RESOURCE' AND n.retracted_at IS NULL AND NOT EXISTS(SELECT 1 FROM retrieval_source_projections s WHERE s.node_id=n.id) ORDER BY n.project_id LIMIT :limit",
                Map.of("limit",Math.min(32,Math.max(1,limit))),UUID.class);
    }
    public Optional<UUID> missingResource(UUID project) {
        return jdbc.queryForList("SELECT n.id FROM nodes n WHERE n.project_id=:project AND n.kind='RESOURCE' AND n.retracted_at IS NULL AND NOT EXISTS(SELECT 1 FROM retrieval_source_projections s WHERE s.node_id=n.id) ORDER BY n.id LIMIT 1",Map.of("project",project),UUID.class).stream().findFirst();
    }
    public List<UUID> embeddingPendingProjects(int limit) {
        return jdbc.queryForList("SELECT DISTINCT e.project_id FROM retrieval_entries e LEFT JOIN retrieval_index_heads h ON h.corpus_id=e.corpus_id WHERE e.project_id IS NOT NULL AND e.retracted_at IS NULL AND length(e.content) BETWEEN 1 AND 12000 AND NOT COALESCE(e.profile_id=:profile AND e.index_generation=h.active_generation AND e.embedding IS NOT NULL,FALSE) ORDER BY e.project_id LIMIT :limit",
                Map.of("profile",PROFILE,"limit",Math.min(32,Math.max(1,limit))),UUID.class);
    }
    @Transactional public void enqueue(Node node,Map<String,Object> metadata) {
        entries.deleteSourcePrefix(node.projectId(),"resource-chunk:"+node.id()+":");
        if(node.retractedAt()!=null || node.contentText()==null || node.contentText().isBlank() || node.contentText().length()>200000
                || !new RetrievalSourcePolicy().allow(node) || !new RetrievalSourcePolicy().allowMetadata(metadata)) {
            // An exclusion marker contains no rejected raw content or source-controlled metadata.
            jdbc.update("""
                INSERT INTO retrieval_source_projections(node_id,project_id,source_version,raw_text,raw_hash,metadata,state)
                VALUES(:node,:project,:version,'',:hash,'{}'::jsonb,'SKIPPED')
                ON CONFLICT(node_id) DO UPDATE SET source_version=EXCLUDED.source_version,raw_text='',raw_hash=EXCLUDED.raw_hash,
                    metadata='{}'::jsonb,state='SKIPPED',updated_at=now()
                """,Map.of("node",node.id(),"project",node.projectId(),"version",UUID.randomUUID(),"hash",Hashes.sha256Hex(""))); return;
        }
        jdbc.update("""
                INSERT INTO retrieval_source_projections(node_id,project_id,source_version,raw_text,raw_hash,metadata,state)
                VALUES(:node,:project,:version,:text,:hash,CAST(:metadata AS jsonb),'PENDING')
                ON CONFLICT(node_id) DO UPDATE SET source_version=EXCLUDED.source_version,raw_text=EXCLUDED.raw_text,
                    raw_hash=EXCLUDED.raw_hash,metadata=EXCLUDED.metadata,state='PENDING',updated_at=now()
                """,Map.of("node",node.id(),"project",node.projectId(),"version",UUID.randomUUID(),"text",node.contentText(),
                "hash",Hashes.sha256Hex(node.contentText()),"metadata",write(metadata)));
    }
    public List<UUID> pendingProjects(int limit) {
        return jdbc.queryForList("SELECT DISTINCT project_id FROM retrieval_source_projections WHERE state='PENDING' ORDER BY project_id LIMIT :limit",
                Map.of("limit",Math.min(32,Math.max(1,limit))),UUID.class);
    }
    @Transactional public Optional<SplitRequest> claim(UUID project,UUID generation) {
        var generationRows=jdbc.queryForList("SELECT id FROM retrieval_index_generations WHERE id=:id AND corpus_id=:project AND profile_id=:profile AND state IN ('ACTIVE','PREPARING') FOR UPDATE",
                Map.of("id",generation,"project",project,"profile",PROFILE));
        if(generationRows.size()!=1) throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        jdbc.update("UPDATE retrieval_index_jobs SET state='STALE' WHERE corpus_id=:project AND state='CLAIMED' AND deadline<=clock_timestamp()",Map.of("project",project));
        var rows=jdbc.queryForList("""
                SELECT * FROM retrieval_source_projections s WHERE project_id=:project AND state='PENDING'
                AND NOT EXISTS(SELECT 1 FROM retrieval_index_jobs j WHERE j.corpus_id=:project AND j.state='CLAIMED'
                    AND j.deadline>clock_timestamp() AND j.request->>'sourceRef'='resource:'||s.node_id::text)
                ORDER BY node_id LIMIT 1 FOR UPDATE OF s
                """,Map.of("project",project));
        if(rows.isEmpty()) return Optional.empty();
        var row=rows.getFirst();
        return createSplitJob(project,project,generation,"resource:"+row.get("node_id"),row.get("source_version").toString(),
                "RESOURCE_CHUNK",row.get("raw_hash").toString(),row.get("raw_text").toString());
    }
    @Transactional public Optional<SplitRequest> createSplitJob(UUID corpus,UUID project,UUID generation,String ref,String version,String kind,String hash,String text) {
        var generations=jdbc.queryForList("SELECT profile_id FROM retrieval_index_generations WHERE id=:id AND corpus_id=:corpus AND state IN ('ACTIVE','PREPARING') FOR UPDATE",Map.of("id",generation,"corpus",corpus));
        if(generations.size()!=1)
            throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
        String profile=generations.getFirst().get("profile_id").toString();
        if(project!=null && !PROFILE.equals(profile)) throw new IllegalStateException("UNSUPPORTED_PROFILE");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_index_jobs WHERE corpus_id=:corpus AND state='CLAIMED' AND deadline>clock_timestamp() AND request->>'sourceRef'=:ref)",Map.of("corpus",corpus,"ref",ref),Boolean.class))) return Optional.empty();
        UUID job=UUID.randomUUID(),lease=UUID.randomUUID(),grant=UUID.randomUUID(),request=UUID.randomUUID();
        Instant deadline=Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(30);
        var split=new SplitRequest(PROFILE.equals(profile)?"retrieval.v1":"retrieval.v2",request,new Workload("INDEX_JOB",job,1),new Grant(grant,1,deadline),profile,generation,
                deadline,job,lease,0,ref,version,kind,hash,text);
        var scope=new RetrievalStore.Scope(corpus,project,null,Set.of(project==null?"HELP":"RESOURCE"),Set.of(),Set.of(),Set.of(),"",48,12,12000);
        jdbc.update("""
                INSERT INTO retrieval_scope_grants(id,request_id,corpus_id,project_id,workload_kind,workload_id,epoch,profile_id,index_generation,deadline,scope)
                VALUES(:grant,:request,:corpus,:project,'INDEX_JOB',:job,1,:profile,:generation,:deadline,CAST(:scope AS jsonb))
                """,Maps.of("grant",grant,"request",request,"corpus",corpus,"project",project,"job",job,"profile",profile,"generation",generation,
                "deadline",Timestamp.from(deadline),"scope",write(scope)));
        jdbc.update("""
                INSERT INTO retrieval_index_jobs(id,corpus_id,index_generation,profile_id,lease_id,state,deadline,request)
                VALUES(:job,:corpus,:generation,:profile,:lease,'CLAIMED',:deadline,CAST(:request AS jsonb))
                """,Map.of("job",job,"corpus",corpus,"generation",generation,"profile",profile,"lease",lease,"deadline",Timestamp.from(deadline),"request",write(split)));
        return Optional.of(split);
    }
    public SplitRequest validateGrant(SplitRequest request) {
        store.guard(request);
        var rows=jdbc.queryForList("SELECT request FROM retrieval_index_jobs WHERE id=:job AND lease_id=:lease AND state='CLAIMED' AND version=:version",
                Map.of("job",request.jobId(),"lease",request.leaseId(),"version",request.expectedVersion()));
        if(rows.size()!=1 || !write(read(rows.getFirst().get("request").toString(),SplitRequest.class)).equals(write(request)))
            throw new IllegalStateException("STALE_WORKLOAD");
        current(request,false);
        return request;
    }
    private Map<String,Object> current(SplitRequest request,boolean locked) {
        UUID nodeId=UUID.fromString(request.sourceRef().substring("resource:".length()));
        // Canonical node first, consistently with node writes/deletion; stale raw snapshots cannot authorize work.
        var canonical=jdbc.queryForList("SELECT id FROM nodes WHERE id=:id"+(locked?" FOR UPDATE":""),Map.of("id",nodeId));
        var rows=jdbc.queryForList("SELECT * FROM retrieval_source_projections WHERE node_id=:id AND source_version=:version"+(locked?" FOR UPDATE":""),
                Map.of("id",nodeId,"version",UUID.fromString(request.sourceVersion())));
        var node=nodes.findById(nodeId);
        if(canonical.size()!=1 || rows.size()!=1 || node.isEmpty() || !new RetrievalSourcePolicy().allow(node.get())
                || node.get().retractedAt()!=null || node.get().kind()!=NodeKind.RESOURCE || !request.text().equals(node.get().contentText())
                || !request.text().equals(rows.getFirst().get("raw_text")) || !request.contentHash().equals(rows.getFirst().get("raw_hash")))
            throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        return rows.getFirst();
    }
    public static void verifySlices(SplitRequest request,List<SourceChunk> chunks) {
        if(chunks==null || chunks.isEmpty() || chunks.size()>256) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        int offset=0,index=0;
        for(var chunk:chunks) {
            if(chunk.index()!=index++ || chunk.startOffset()!=offset || chunk.endOffset()>request.text().length()
                    || chunk.endOffset()<=offset || chunk.text()==null || chunk.text().length()>12000
                    || !request.text().substring(offset,chunk.endOffset()).equals(chunk.text())
                    || !Hashes.sha256Hex(chunk.text()).equals(chunk.contentHash()) || chunk.headings()==null || chunk.headings().size()>6)
                throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
            offset=chunk.endOffset();
        }
        if(offset!=request.text().length()) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
    }
    @Transactional public boolean commit(SplitResult result) {
        var jobs=jdbc.queryForList("SELECT * FROM retrieval_index_jobs WHERE id=:id FOR UPDATE",Map.of("id",result.jobId()));
        if(jobs.size()!=1) throw new IllegalStateException("STALE_WORKLOAD");
        var job=jobs.getFirst(); var request=read(job.get("request").toString(),SplitRequest.class);
        if(!binding(request).equals(binding(result)) || !request.leaseId().equals(result.leaseId()) || request.expectedVersion()!=result.expectedVersion()
                || !request.sourceRef().equals(result.sourceRef()) || !request.sourceVersion().equals(result.sourceVersion())
                || !request.contentHash().equals(result.contentHash())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        String resultHash=Hashes.sha256Hex(write(result));
        if("SUCCEEDED".equals(job.get("state"))) {
            if(!resultHash.equals(job.get("result_hash"))) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
            return false;
        }
        store.guard(request);
        var row=current(request,true); UUID project=(UUID)row.get("project_id"),nodeId=(UUID)row.get("node_id");
        verifySlices(request,result.chunks());
        entries.deleteSourcePrefix(project,"resource-chunk:"+nodeId+":");
        var base=read(row.get("metadata").toString(),Map.class);
        UUID route=null; var routes=base.get("originRouteIds");
        if(routes instanceof List<?> ids && ids.size()==1) route=UUID.fromString(ids.getFirst().toString());
        for(var chunk:result.chunks()) {
            if(chunk.text().isBlank()) continue;
            var metadata=new LinkedHashMap<String,Object>(base);
            metadata.put("chunkIndex",chunk.index()); metadata.put("chunk",chunk.index());
            metadata.put("startOffset",chunk.startOffset()); metadata.put("endOffset",chunk.endOffset());
            metadata.put("lineRange",List.of(chunk.startOffset(),chunk.endOffset())); metadata.put("headings",chunk.headings());
            metadata.put("rawSourceVersion",request.sourceVersion()); metadata.put("rawContentHash",request.contentHash());
            String ref="resource-chunk:"+nodeId+":"+chunk.index();
            entries.upsert(new RetrievalEntry(UUID.nameUUIDFromBytes((project+":"+ref).getBytes(StandardCharsets.UTF_8)),project,route,
                    RetrievalSourceKind.RESOURCE_CHUNK,nodeId,ref,RetrievalScope.RESOURCE,MemoryAuthority.EXTERNAL_EVIDENCE,
                    chunk.text(),chunk.contentHash(),metadata,null,null,"PENDING",null));
        }
        jdbc.update("UPDATE retrieval_source_projections SET state='READY' WHERE node_id=:id AND source_version=:version",Map.of("id",nodeId,"version",UUID.fromString(request.sourceVersion())));
        if(jdbc.update("UPDATE retrieval_index_jobs SET state='SUCCEEDED',result_hash=:hash,version=version+1 WHERE id=:id AND state='CLAIMED' AND lease_id=:lease AND version=:version AND deadline>clock_timestamp()",
                Map.of("id",result.jobId(),"hash",resultHash,"lease",result.leaseId(),"version",result.expectedVersion()))!=1)
            throw new IllegalStateException("STALE_WORKLOAD");
        return true;
    }
}
