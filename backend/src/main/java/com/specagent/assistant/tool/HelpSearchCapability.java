package com.specagent.assistant.tool;

import com.specagent.capability.*;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** Curated product help through the exact shared Python RAG service. */
@Component
@ConditionalOnExpression("'${spec.agent.retrieval.engine:java-hybrid.v1}' == 'python-rag.v1' && '${spec.global-assistant.engine:java-legacy.v1}' == 'langchain-ga.v1'")
public class HelpSearchCapability implements PreparedCapabilityAdapter {
    private final SharedRetrievalHost retrieval;
    private final RetrievalStore store;
    public HelpSearchCapability(SharedRetrievalHost retrieval,RetrievalStore store) { this.retrieval=retrieval; this.store=store; }
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("help.search","1","Search curated Spec Agent product help using shared Python RAG. Help is a separate allowed corpus in the same index, contains no project/user facts, and cannot confirm graph requirements. Returns original excerpts, versions and positions. Limit 1-8 results, 8000 chars. No arbitrary file, URL or database access.",
            Map.of("query",Map.of("type","string","required",true,"maxLength",2000),"limit",Map.of("type","integer","required",false,"minimum",1,"maximum",8)),
            Map.of("sources",Map.of("type","array")),true,SideEffectClass.NONE,List.of(),List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    public CapabilityResult invoke(CapabilityInvocation invocation) { return GlobalAssistantToolFailures.failed(invocation,"help.search","RETRIEVAL_UNAVAILABLE","Use authorized GA preparation"); }
    public Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> arguments,BooleanSupplier active) { throw new IllegalStateException("STALE_WORKLOAD"); }
    public Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> arguments,BooleanSupplier active,UUID run) {
        if(!Set.of("query","limit").containsAll(arguments.keySet()) || !(arguments.get("query") instanceof String query) || query.isBlank() || query.length()>2000
                || !GlobalAssistantToolCatalog.isValidLimit(arguments.get("limit")) || GlobalAssistantToolCatalog.limitOrDefault(arguments.get("limit"),5)>8)
            return invocation->GlobalAssistantToolFailures.failed(invocation,"help.search","TOOL_ARGUMENT_INVALID","Invalid bounded help query");
        if(run==null || !active.getAsBoolean()) throw new IllegalStateException("STALE_WORKLOAD");
        return RetrievalToolFailures.guarded(descriptor().capabilityId(),active,() -> {
        var result=retrieval.searchHelp(query,GlobalAssistantToolCatalog.limitOrDefault(arguments.get("limit"),5),run);
        return invocation -> {
            if(!run.equals(invocation.runId())) throw new IllegalStateException("STALE_WORKLOAD");
            var checked=store.validateSources(new Validation(result.protocolVersion(),result.requestId(),result.workload(),result.scopeGrant(),result.profileId(),result.indexGeneration(),result.deadline(),result.items().stream().map(Ranked::source).toList()));
            var allowed=new HashSet<>(checked.allowedEntryIds()); var items=result.items().stream().filter(item->allowed.contains(item.source().entryId())).toList();
            return new CapabilityResult(invocation.invocationId(),invocation.invocationKey(),"help.search",CapabilityResult.Status.SUCCEEDED,
                Map.of("sources",items,"retrievalEngineVersion","python-rag.v1","profileId",result.profileId(),"indexGeneration",result.indexGeneration().toString(),"vectorUnavailable",result.vectorUnavailable()),
                items.stream().map(item->item.source().sourceRef()).toList(),Map.of("kind","CURATED_HELP","corpusId",CuratedHelpSources.CORPUS.toString()),result.warnings());
        };
        });
    }
}
