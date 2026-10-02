package com.specagent.retrieval.protocol;

import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.index.RetrievalSourcePolicy;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Host-only grants, scoped SQL and final source/version checks; contains no RRF or embedding provider. */
@Service
public class RetrievalStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final RetrievalSourceVerifier verifier;
    private final RetrievalSourcePolicy sourcePolicy=new RetrievalSourcePolicy();
    public RetrievalStore(NamedParameterJdbcTemplate jdbc,RetrievalSourceVerifier verifier) { this.jdbc=jdbc; this.verifier=verifier; }
    public record Scope(UUID corpusId,UUID projectId,UUID routeId,Set<String> scopes,Set<String> routeRefs,
                        Set<String> graphRefs,Set<String> excludedRefs,String query,int laneLimit,int maxItems,int maxChars) {}

    /** Initial empty generation is valid: lexical retrieval need not wait for vector backfill. */
    @Transactional
    public UUID ensureProjectGeneration(UUID project) {
        if(jdbc.queryForList("SELECT id FROM projects WHERE id=:id FOR UPDATE",Map.of("id",project)).isEmpty())
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        var existing=jdbc.queryForList("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=:id",Map.of("id",project));
        if(!existing.isEmpty()) return (UUID)existing.getFirst().get("active_generation");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO retrieval_index_generations(id,corpus_id,profile_id,state) VALUES(:id,:corpus,:profile,'ACTIVE')",
                Map.of("id",id,"corpus",project,"profile",PROFILE));
        jdbc.update("INSERT INTO retrieval_index_heads(corpus_id,active_generation,profile_id) VALUES(:corpus,:id,:profile)",
                Map.of("id",id,"corpus",project,"profile",PROFILE));
        return id;
    }

    @Transactional
    public Search issueProject(RetrievalQuery query,UUID workloadId,String workloadKind,Set<String> excludedRefs) {
        UUID generation=ensureProjectGeneration(query.projectId());
        Scope scope=new Scope(query.projectId(),query.projectId(),query.routeId(),
                query.scopes().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),query.routeSourceRefs(),
                query.graphNodeIds().stream().map(id->"node:"+id).collect(java.util.stream.Collectors.toSet()),Set.copyOf(excludedRefs),
                query.queryText(),48,Math.max(1,Math.min(12,query.maxItems())),Math.max(1,Math.min(12000,query.maxChars())));
        validateScope(scope);
        return issue(scope,generation,workloadId,workloadKind);
    }
    @Transactional public UUID ensureHelpGeneration() {
        UUID corpus=CuratedHelpSources.CORPUS;
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:key))",Map.of("key",corpus.toString()));
        var existing=jdbc.queryForList("SELECT active_generation FROM retrieval_index_heads WHERE corpus_id=:corpus",Map.of("corpus",corpus));
        if(!existing.isEmpty()) return (UUID)existing.getFirst().get("active_generation");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO retrieval_index_generations(id,corpus_id,profile_id,state) VALUES(:id,:corpus,:profile,'ACTIVE')",Map.of("id",id,"corpus",corpus,"profile",PROFILE));
        jdbc.update("INSERT INTO retrieval_index_heads(corpus_id,active_generation,profile_id) VALUES(:corpus,:id,:profile)",Map.of("corpus",corpus,"id",id,"profile",PROFILE));
        return id;
    }
    @Transactional public Search issueHelp(String query,int limit,UUID workload) {
        if(query==null || query.isBlank() || query.length()>2000) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        var scope=new Scope(CuratedHelpSources.CORPUS,null,null,Set.of("HELP"),Set.of(),Set.of(),Set.of(),query,48,Math.max(1,Math.min(8,limit)),8000);
        return issue(scope,ensureHelpGeneration(),workload,"GA_RUN");
    }
    private Search issue(Scope scope,UUID generation,UUID workloadId,String workloadKind) {
        UUID request=UUID.randomUUID(),grant=UUID.randomUUID(); Instant deadline=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(30);
        Workload workload=new Workload(workloadKind,workloadId,1);
        String profile=(String)jdbc.queryForList("SELECT profile_id FROM retrieval_index_generations WHERE id=:id",Map.of("id",generation)).getFirst().get("profile_id");
        jdbc.update("""
                INSERT INTO retrieval_scope_grants(id,request_id,corpus_id,project_id,route_id,workload_kind,workload_id,epoch,
                    profile_id,index_generation,deadline,scope)
                VALUES(:id,:request,:corpus,:project,:route,:kind,:workload,1,:profile,:generation,:deadline,CAST(:scope AS jsonb))
                """,Maps.of("id",grant,"request",request,"corpus",scope.corpusId(),"project",scope.projectId(),"route",scope.routeId(),
                "kind",workloadKind,"workload",workloadId,"profile",profile,"generation",generation,
                "deadline",Timestamp.from(deadline),"scope",write(scope)));
        return new Search(protocol(profile),request,workload,new Grant(grant,1,deadline),profile,generation,deadline,
                "python-rag.v1",scope.query(),"HYBRID",new Limits(scope.maxItems(),scope.maxChars(),scope.laneLimit()));
    }

    public Scope guard(Envelope request) {
        validate(request);
        dimensions(request.profileId());
        if(!request.deadline().isAfter(Instant.now())) throw new IllegalStateException("DEADLINE_EXCEEDED");
        var rows=jdbc.queryForList("SELECT * FROM retrieval_scope_grants WHERE id=:id AND deadline>clock_timestamp() AND revoked=FALSE",
                Map.of("id",request.scopeGrant().grantId()));
        if(rows.size()!=1) throw new IllegalStateException("STALE_SCOPE_GRANT");
        var row=rows.getFirst(); Instant deadline=((Timestamp)row.get("deadline")).toInstant();
        if(!row.get("request_id").equals(request.requestId()) || !row.get("profile_id").equals(request.profileId())
                || !row.get("index_generation").equals(request.indexGeneration()) || !row.get("workload_kind").equals(request.workload().kind())
                || !row.get("workload_id").equals(request.workload().id()) || ((Number)row.get("epoch")).longValue()!=request.workload().executionEpoch()
                || ((Number)row.get("version")).longValue()!=request.scopeGrant().version()
                || !deadline.equals(request.deadline()) || !deadline.equals(request.scopeGrant().expiresAt()))
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        Scope scope=read(row.get("scope").toString(),Scope.class); validateScope(scope);
        boolean indexJob="INDEX_JOB".equals(request.workload().kind());
        if(indexJob) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_index_jobs WHERE id=:id AND epoch=:epoch AND state='CLAIMED' AND deadline>clock_timestamp())",
                    Map.of("id",request.workload().id(),"epoch",request.workload().executionEpoch()),Boolean.class)))
                throw new IllegalStateException("STALE_WORKLOAD");
        } else {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_index_heads WHERE corpus_id=:corpus AND active_generation=:generation AND profile_id=:profile)",
                    Map.of("corpus",scope.corpusId(),"generation",request.indexGeneration(),"profile",request.profileId()),Boolean.class)))
                throw new IllegalStateException("INDEX_GENERATION_MISMATCH");
            if("GA_RUN".equals(request.workload().kind()) && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM global_assistant_runs r JOIN ga_executions e ON e.run_id=r.id
                    WHERE r.id=:id AND r.status='RUNNING' AND r.cancel_requested_at IS NULL
                      AND e.execution_epoch=:epoch AND e.deadline>clock_timestamp())
                    """,Map.of("id",request.workload().id(),"epoch",request.workload().executionEpoch()),Boolean.class)))
                throw new IllegalStateException("STALE_WORKLOAD");
            if("PROJECT_RUN".equals(request.workload().kind()) && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_runs WHERE id=:id AND project_id=:project AND status IN ('CREATED','RUNNING'))",
                    Map.of("id",request.workload().id(),"project",scope.projectId()),Boolean.class)))
                throw new IllegalStateException("STALE_WORKLOAD");
        }
        return scope;
    }

    private void validateScope(Scope scope) {
        if(scope.projectId()==null && (!CuratedHelpSources.CORPUS.equals(scope.corpusId()) || !scope.scopes().equals(Set.of("HELP")) || scope.routeId()!=null))
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        if(scope.projectId()!=null && (!scope.projectId().equals(scope.corpusId()) || scope.scopes().contains("HELP")))
            throw new IllegalStateException("STALE_SCOPE_GRANT");
        if(scope.projectId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE id=:id)",
                Map.of("id",scope.projectId()),Boolean.class))) throw new IllegalStateException("STALE_SCOPE_GRANT");
        if(scope.routeId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM routes WHERE id=:id AND project_id=:project AND lifecycle_status='open')",
                Map.of("id",scope.routeId(),"project",scope.projectId()),Boolean.class))) throw new IllegalStateException("STALE_SCOPE_GRANT");
    }

    public CandidatesResult candidates(Candidates request) {
        Scope scope=guard(request);
        if(request.query()==null || !scope.query().equals(request.query()) || request.lanes()==null || request.lanes().isEmpty()
                || request.lanes().size()>8 || new HashSet<>(request.lanes()).size()!=request.lanes().size()
                || !LANES.containsAll(request.lanes()) || request.laneLimit()<1 || request.laneLimit()>scope.laneLimit()
                || request.lanes().contains("vector")!=(request.queryVector()!=null)) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        if(!Double.isFinite(request.maxVectorDistance()) || request.maxVectorDistance()<0 || request.maxVectorDistance()>0.65) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        if(request.queryVector()!=null) { request.queryVector().validate();
            if(request.queryVector().dimensions()!=dimensions(request.profileId())) throw new IllegalArgumentException("INVALID_VECTOR"); }
        List<Lane> result=new ArrayList<>();
        for(String lane:request.lanes()) {
            if(!allowedLane(scope,lane)) continue;
            var params=new LinkedHashMap<String,Object>(Map.of("corpus",scope.corpusId(),"query",request.query(),"limit",request.laneLimit()));
            String laneFilter="",order="source_ref",relevance="TRUE";
            if(lane.startsWith("route-")) {
                if(scope.routeRefs().isEmpty()) { result.add(new Lane(lane,List.of())); continue; }
                laneFilter=" AND source_ref IN (:refs)"; params.put("refs",scope.routeRefs());
            } else if(lane.startsWith("resource-")) laneFilter=" AND source_kind='RESOURCE_CHUNK'";
            else if(lane.startsWith("project-")) laneFilter=" AND scope IN ('PROJECT','HELP')";
            else if(lane.equals("graph")) {
                if(scope.graphRefs().isEmpty()) { result.add(new Lane(lane,List.of())); continue; }
                laneFilter=" AND source_ref IN (:refs)"; params.put("refs",scope.graphRefs());
            }
            if(lane.endsWith("lexical")) {
                relevance="(search_vector @@ plainto_tsquery('simple',:query) OR position(lower(:query) in lower(source_ref))>0 OR position(lower(:query) in lower(content))>0)";
                order="ts_rank_cd(search_vector,plainto_tsquery('simple',:query)) DESC, source_ref";
            } else if(lane.endsWith("trigram")) {
                relevance="(similarity(content,:query)>0.15 OR word_similarity(:query,content)>0.15)";
                order="GREATEST(similarity(content,:query),word_similarity(:query,content)) DESC, source_ref";
            } else if(lane.equals("vector")) {
                params.put("vector",vectorLiteral(request.queryVector().values())); params.put("profile",request.profileId());
                params.put("generation",request.indexGeneration()); params.put("dimensions",dimensions(request.profileId()));
                laneFilter=" AND embedding IS NOT NULL AND embedding_status='READY' AND embedding_dimensions=:dimensions AND profile_id=:profile AND index_generation=:generation";
                params.put("maxVectorDistance",request.maxVectorDistance());
                relevance="embedding <=> CAST(:vector AS vector) <= :maxVectorDistance"; order="embedding <=> CAST(:vector AS vector), source_ref";
            }
            String sql="SELECT * FROM retrieval_entries WHERE corpus_id=:corpus AND retracted_at IS NULL AND "+relevance+laneFilter
                    +authorizedFilter(scope,params)+" ORDER BY "+order+" LIMIT :limit";
            if(lane.equals("vector")) {
                // Materialize scope/profile/dimension filters BEFORE any distance operator; mixed dimensions are legal in this table.
                sql="WITH eligible AS MATERIALIZED (SELECT * FROM retrieval_entries WHERE corpus_id=:corpus AND retracted_at IS NULL"+laneFilter+authorizedFilter(scope,params)+") SELECT * FROM eligible WHERE "+relevance+" ORDER BY "+order+" LIMIT :limit";
            }
            var sources=jdbc.queryForList(sql,params).stream().filter(row->row.get("content") instanceof String text && !text.isBlank() && text.length()<=12000)
                    .filter(verifier::current).map(row->source(row,scope))
                    .filter(source->sourcePolicy.allowText(source.content()) && !source.content().isBlank() && source.content().length()<=12000).toList();
            result.add(new Lane(lane,sources));
        }
        guard(request); // Fence lifecycle/profile changes during candidate SQL.
        return new CandidatesResult(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),
                request.profileId(),request.indexGeneration(),request.deadline(),List.copyOf(result));
    }

    public ValidationResult validateSources(Validation request) {
        Scope scope=guard(request);
        if(request.sources()==null || request.sources().size()>scope.maxItems() || request.sources().size()>12
                || request.sources().stream().map(Source::entryId).distinct().count()!=request.sources().size())
            throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        List<UUID> allowed=new ArrayList<>(),rejected=new ArrayList<>();
        for(Source source:request.sources()) {
            var params=new LinkedHashMap<String,Object>(Map.of("id",source.entryId(),"corpus",scope.corpusId()));
            var rows=jdbc.queryForList("SELECT * FROM retrieval_entries WHERE id=:id AND corpus_id=:corpus AND retracted_at IS NULL"+authorizedFilter(scope,params),params);
            if(rows.size()==1 && verifier.current(rows.getFirst()) && source.equals(source(rows.getFirst(),scope)) && sourcePolicy.allowText(source.content())) allowed.add(source.entryId());
            else rejected.add(source.entryId());
        }
        guard(request);
        return new ValidationResult(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),request.profileId(),
                request.indexGeneration(),request.deadline(),List.copyOf(allowed),List.copyOf(rejected));
    }

    private boolean allowedLane(Scope scope,String lane) {
        if(lane.startsWith("route-") || lane.equals("graph")) return scope.scopes().contains("ROUTE");
        if(lane.startsWith("project-")) return scope.scopes().contains("PROJECT") || scope.scopes().contains("HELP");
        if(lane.startsWith("resource-")) return scope.scopes().contains("RESOURCE");
        return lane.equals("vector");
    }
    private String authorizedFilter(Scope scope,Map<String,Object> params) {
        List<String> clauses=new ArrayList<>();
        if(scope.scopes().contains("PROJECT")) clauses.add("scope='PROJECT'");
        if(scope.scopes().contains("HELP")) clauses.add("scope='HELP'");
        if(scope.scopes().contains("RESOURCE")) clauses.add("scope='RESOURCE'");
        if(scope.scopes().contains("ROUTE") && !scope.routeRefs().isEmpty()) {
            params.put("authorizedRefs",scope.routeRefs()); clauses.add("source_ref IN (:authorizedRefs)");
        }
        String sql=" AND ("+(clauses.isEmpty()?"FALSE":String.join(" OR ",clauses))+")";
        if(!scope.excludedRefs().isEmpty()) { params.put("excluded",scope.excludedRefs()); sql+=" AND source_ref NOT IN (:excluded)"; }
        // A removed route cannot continue granting visibility through stale derived provenance.
        sql+=" AND (route_id IS NULL OR EXISTS(SELECT 1 FROM routes r WHERE r.id=retrieval_entries.route_id AND r.lifecycle_status='open'))";
        return sql;
    }

    public static String protocol(String profile) { return PROFILE.equals(profile)?"retrieval.v1":"retrieval.v2"; }
    public String activeHelpProfile() {
        var rows=jdbc.queryForList("SELECT profile_id FROM retrieval_index_heads WHERE corpus_id=:id",Map.of("id",CuratedHelpSources.CORPUS));
        return rows.isEmpty()?PROFILE:(String)rows.getFirst().get("profile_id");
    }
    public int dimensions(String profile) {
        if(PROFILE.equals(profile)) return 1024;
        var rows=jdbc.queryForList("SELECT (semantic->>'dimensions')::integer AS dimensions FROM embedding_profiles WHERE profile_id=:profile",Map.of("profile",profile));
        if(rows.size()!=1) throw new IllegalStateException("UNSUPPORTED_PROFILE");
        int value=((Number)rows.getFirst().get("dimensions")).intValue();
        if(value<1 || value>4096) throw new IllegalStateException("UNSUPPORTED_PROFILE");
        return value;
    }
    public String model(String profile) {
        return PROFILE.equals(profile)?"qwen3-embedding:0.6b":jdbc.queryForObject("SELECT semantic->>'modelTag' FROM embedding_profiles WHERE profile_id=:profile",Map.of("profile",profile),String.class);
    }
    public boolean storageReady() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('retrieval_entries') IS NOT NULL AND to_regclass('retrieval_scope_grants') IS NOT NULL AND EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')",Map.of(),Boolean.class));
    }
    public boolean canonicalCurrent(Map<String,Object> row) { return verifier.current(row); }
    public Source source(Map<String,Object> row,Scope scope) {
        UUID route=(UUID)row.get("route_id"); String ref=(String)row.get("source_ref"),kind=(String)row.get("source_kind");
        var metadata=read(row.get("metadata").toString(),Map.class);
        Integer chunk=integer(metadata.get("chunkIndex")),start=integer(metadata.get("startOffset")),end=integer(metadata.get("endOffset"));
        if(chunk==null) chunk=integer(metadata.get("chunk"));
        String authority=(String)row.get("authority"),sourceScope=(String)row.get("scope");
        boolean routeSource=scope.routeRefs().contains(ref) && scope.routeId()!=null;
        if(routeSource && !kind.equals("RESOURCE_CHUNK")) sourceScope="ROUTE";
        int tier=authority.equals("REJECTED")?4:routeSource?0:Set.of("CONFIRMED","USER_AUTHORED").contains(authority)?1:
                route!=null && scope.routeId()!=null && !route.equals(scope.routeId())?3:2;
        return new Source((UUID)row.get("id"),(UUID)row.get("corpus_id"),(UUID)row.get("project_id"),
                routeSource?scope.routeId():route,ref,row.get("source_version").toString(),(String)row.get("content_hash"),kind,
                sourceScope,authority,tier,(String)row.get("content"),new Location(chunk,start,end));
    }
    /** Metadata for an already verified immutable result; a newer projection cannot be mixed into it. */
    public Map<String,Object> metadataFor(Source source) {
        var rows=jdbc.queryForList("SELECT metadata FROM retrieval_entries WHERE id=:id AND corpus_id=:corpus AND source_version=:version AND content_hash=:hash AND retracted_at IS NULL",
                Map.of("id",source.entryId(),"corpus",source.corpusId(),"version",source.sourceVersion(),"hash",source.contentHash()));
        if(rows.size()!=1) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        var result=new LinkedHashMap<String,Object>(read(rows.getFirst().get("metadata").toString(),Map.class));
        result.put("sourceVersion",source.sourceVersion()); return result;
    }
    private Integer integer(Object value) { return value instanceof Number number ? number.intValue():null; }
    public static String vectorLiteral(double[] vector) {
        StringJoiner values=new StringJoiner(",","[","]"); for(double value:vector) values.add(Float.toString((float)value)); return values.toString();
    }
}
