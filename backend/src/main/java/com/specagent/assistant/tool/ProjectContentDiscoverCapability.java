package com.specagent.assistant.tool;

import com.specagent.capability.*;
import com.specagent.retrieval.*;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** Bounded application discovery observations, not graph truth or mandatory project context. */
@Component
public class ProjectContentDiscoverCapability implements PreparedCapabilityAdapter {
    private final SharedRetrievalHost retrieval;
    private final RetrievalStore store;
    private final NamedParameterJdbcTemplate jdbc;
    public ProjectContentDiscoverCapability(GaRetrievalAccess retrieval,RetrievalStore store,NamedParameterJdbcTemplate jdbc) {
        this.retrieval=retrieval.host(); this.store=store; this.jdbc=jdbc;
    }
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("project.content.discover","1",
            "Search actual project node/resource content using shared Python RAG. With projectId search only that project; otherwise search at most 4 most recently updated projects. Returns candidate projects and versioned evidence, not confirmed graph facts. No whole-history scan, no external writes. Limit 1-8 sources per project, total excerpts at most 4000 chars per project.",
            Map.of("query",Map.of("type","string","required",true,"maxLength",2000),"projectId",Map.of("type","string","required",false),
                    "limit",Map.of("type","integer","required",false,"minimum",1,"maximum",8)),Map.of("candidates",Map.of("type","array")),
            true,SideEffectClass.NONE,List.of(),List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        return GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),"RETRIEVAL_UNAVAILABLE","Use the authorized GA preparation boundary");
    }
    public Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> arguments,BooleanSupplier active) {
        throw new IllegalStateException("STALE_WORKLOAD");
    }
    public Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> arguments,BooleanSupplier active,UUID run) {
        if(!Set.of("query","projectId","limit").containsAll(arguments.keySet()) || !(arguments.get("query") instanceof String query)
                || query.isBlank() || query.length()>2000 || !GlobalAssistantToolCatalog.isValidLimit(arguments.get("limit"))
                || GlobalAssistantToolCatalog.limitOrDefault(arguments.get("limit"),5)>8)
            return invocation->GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),"TOOL_ARGUMENT_INVALID","Invalid bounded content query");
        if(run==null || !active.getAsBoolean()) throw new IllegalStateException("STALE_WORKLOAD");
        return RetrievalToolFailures.guarded(descriptor().capabilityId(),active,() -> {
        List<UUID> projects;
        if(arguments.containsKey("projectId")) {
            try { projects=List.of(UUID.fromString((String)arguments.get("projectId"))); }
            catch(RuntimeException invalid) { return invocation->GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),"TOOL_ARGUMENT_INVALID","Invalid project identity"); }
        } else projects=jdbc.queryForList("SELECT id FROM projects ORDER BY updated_at DESC,id LIMIT 4",Map.of(),UUID.class);
        List<SearchResult> results=new ArrayList<>(); Map<UUID,String> titles=new LinkedHashMap<>();
        Set<String> preparationWarnings=new LinkedHashSet<>();
        for(UUID project:projects) {
            if(!active.getAsBoolean()) throw new IllegalStateException("STALE_WORKLOAD");
            var rows=jdbc.queryForList("SELECT title FROM projects WHERE id=:id",Map.of("id",project));
            if(rows.isEmpty()) continue;
            boolean indexed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_index_heads h JOIN retrieval_entries e ON e.corpus_id=h.corpus_id AND e.index_generation=h.active_generation AND e.profile_id=h.profile_id WHERE h.corpus_id=:project AND h.profile_id=:profile AND e.embedding_status='READY' AND e.retracted_at IS NULL)",Map.of("project",project,"profile",PROFILE),Boolean.class));
            if(!indexed) { preparationWarnings.add("PROJECT_INDEX_NOT_READY"); continue; }
            titles.put(project,rows.getFirst().get("title").toString());
            var request=new RetrievalQuery(project,null,null,"EXPLICIT_SEARCH",query,List.of(RetrievalScope.PROJECT,RetrievalScope.RESOURCE),
                    GlobalAssistantToolCatalog.limitOrDefault(arguments.get("limit"),5),4000,Set.of(),Set.of());
            results.add(retrieval.search(request,run,"GA_RUN",Set.of()));
        }
        return invocation -> {
            if(!run.equals(invocation.runId())) throw new IllegalStateException("STALE_WORKLOAD");
            if(results.isEmpty() && !preparationWarnings.isEmpty())
                return GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),"PROJECT_INDEX_NOT_READY","Project content index is not prepared; no search was performed");
            List<Map<String,Object>> candidates=new ArrayList<>(); List<String> refs=new ArrayList<>(); Set<String> warnings=new LinkedHashSet<>(preparationWarnings);
            for(var result:results) {
                var validated=store.validateSources(new Validation(result.protocolVersion(),result.requestId(),result.workload(),result.scopeGrant(),
                    result.profileId(),result.indexGeneration(),result.deadline(),result.items().stream().map(Ranked::source).toList()));
                var allowed=new HashSet<>(validated.allowedEntryIds());
                var items=result.items().stream().filter(item->allowed.contains(item.source().entryId())).toList();
                if(items.isEmpty()) continue;
                UUID project=items.getFirst().source().projectId();
                candidates.add(Map.of("projectId",project.toString(),"title",titles.get(project),"sources",items,
                    "profileId",result.profileId(),"indexGeneration",result.indexGeneration().toString(),"vectorUnavailable",result.vectorUnavailable()));
                items.forEach(item->refs.add(item.source().sourceRef())); warnings.addAll(result.warnings());
            }
            return new CapabilityResult(invocation.invocationId(),invocation.invocationKey(),descriptor().capabilityId(),CapabilityResult.Status.SUCCEEDED,
                Map.of("candidates",List.copyOf(candidates),"searchedProjects",results.size(),"retrievalEngineVersion","python-rag.v1"),List.copyOf(refs),
                Map.of("kind","PROJECT_CONTENT_CANDIDATES","profileId",PROFILE),List.copyOf(warnings));
        };
        });
    }
}
