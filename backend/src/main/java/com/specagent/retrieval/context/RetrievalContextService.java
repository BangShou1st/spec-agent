package com.specagent.retrieval.context;

import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.retrieval.MemoryAuthority;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.RetrievedContextItem;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.retrieval.persistence.RetrievalEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:RetrievalContextService.java
 *
 * 用途:为 Brain 构建有界、保留溯源的检索式工作记忆。它把上下文快照
 * (ContextSnapshot)转成检索查询,调用混合检索器,再按条数/字符上限裁剪,
 * 供模型在运行时看到项目内的相关记忆。
 */
@Service
public class RetrievalContextService {

    private static final int MAX_ITEMS = 12;
    private static final int MAX_CHARS = 12_000;

    private final HybridRetriever retriever;

    public RetrievalContextService(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    public List<RetrievedContextItem> retrieve(ContextSnapshot snapshot,
                                               Set<String> mandatorySourceRefs,
                                               String queryText) {
        return retrieveWithStatus(snapshot,mandatorySourceRefs,queryText).items();
    }
    public boolean usesSharedPython() { return retriever.usesSharedPython(); }
    public record ContextRetrieval(List<RetrievedContextItem> items,Map<String,Object> state) {}
    public ContextRetrieval retrieveWithStatus(ContextSnapshot snapshot,Set<String> mandatorySourceRefs,String queryText) {
        Set<String> routeRefs = new LinkedHashSet<>();
        snapshot.includedNodeIds().forEach(id -> routeRefs.add("node:" + id));
        snapshot.includedAnswerIds().forEach(id -> routeRefs.add("answer:" + id));
        snapshot.includedPatchIds().forEach(id -> routeRefs.add("patch:" + id));

        Set<UUID> graphNodes = new LinkedHashSet<>();
        if (snapshot.tipNodeId() != null) {
            graphNodes.add(snapshot.tipNodeId());
        }
        graphNodes.addAll(snapshot.relatedNodeIds());

        RetrievalQuery query = new RetrievalQuery(
                snapshot.projectId(), snapshot.routeId(), snapshot.tipNodeId(),
                snapshot.operationType().code(), queryText,
                List.of(RetrievalScope.ROUTE, RetrievalScope.PROJECT, RetrievalScope.RESOURCE),
                MAX_ITEMS, MAX_CHARS, routeRefs, graphNodes);

        Set<String> mandatory = mandatoryRefs(snapshot, mandatorySourceRefs);
        List<HybridRetriever.Candidate> candidates;
        Map<String,Object> state=Map.of();
        if(retriever.usesSharedPython()) {
            try {
                var outcome=retriever.retrieveOutcome(query,snapshot.id(),"CONTEXT_PROJECTION",mandatory);
                candidates=outcome.candidates(); state=outcome.state();
            } catch(IllegalStateException ex) {
                return new ContextRetrieval(List.of(),Map.of("retrievalEngineVersion","python-rag.v1",
                        "profileId",com.specagent.retrieval.protocol.RetrievalWire.PROFILE,"supplementalRetrievalUnavailable",true));
            }
        } else candidates= retriever.retrieve(query);
        Set<String> selectedRefs = new HashSet<>();
        Set<String> selectedContentHashes = new HashSet<>();
        List<RetrievedContextItem> result = new ArrayList<>();
        int chars = 0;
        for (HybridRetriever.Candidate candidate : candidates) {
            RetrievalEntry entry = candidate.entry();
            if (mandatory.contains(entry.sourceRef())
                    || !selectedRefs.add(entry.sourceRef())
                    || !selectedContentHashes.add(entry.contentHash())) {
                continue;
            }
            RetrievedContextItem item = toItem(query, entry, candidate.lanes());
            if (item.content().isBlank()) {
                continue;
            }
            int remaining = MAX_CHARS - chars;
            if (remaining <= 0) {
                break;
            }
            String content = item.content();
            if (content.length() > remaining) {
                if(retriever.usesSharedPython()) continue; // Preserve the source hash/location of shared results.
                content = content.substring(0, remaining);
                item = new RetrievedContextItem(item.sourceRef(), item.sourceKind(), item.scope(),
                        item.originRouteId(), item.authority(), content, item.location(),
                        item.provenance(), item.retrievalReason());
            }
            result.add(item);
            chars += content.length();
            if (result.size() >= MAX_ITEMS) {
                break;
            }
        }
        return new ContextRetrieval(List.copyOf(result),state);
    }

    private Set<String> mandatoryRefs(ContextSnapshot snapshot, Set<String> mandatorySourceRefs) {
        Set<String> mandatory = new HashSet<>();
        if (mandatorySourceRefs != null) {
            mandatory.addAll(mandatorySourceRefs);
        }
        if (snapshot.tipNodeId() != null) {
            mandatory.add("node:" + snapshot.tipNodeId());
        }
        snapshot.relatedNodeIds().forEach(id -> mandatory.add("node:" + id));
        return mandatory;
    }

    private RetrievedContextItem toItem(RetrievalQuery query, RetrievalEntry entry,
                                        List<String> lanes) {
        RetrievalScope scope = classifyScope(query, entry);
        UUID originRouteId = scope == RetrievalScope.ROUTE ? query.routeId() : entry.routeId();
        String reason = switch (scope) {
            case ROUTE -> "current-route-memory";
            case PROJECT -> "workspace-project-memory";
            case RESOURCE -> "resource-knowledge";
        };
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("contentHash", entry.contentHash());
        provenance.put("sourceKind", entry.sourceKind().name());
        provenance.put("retrievalLanes", lanes);
        for(String key:List.of("sourceVersion","retrievalEngineVersion","profileId","indexGeneration","vectorUnavailable"))
            if(entry.metadata().containsKey(key)) provenance.put(key,entry.metadata().get(key));
        putRouteProvenance(entry, provenance);
        return new RetrievedContextItem(entry.sourceRef(), entry.sourceKind(), scope,
                originRouteId, entry.authority(), entry.content(),
                location(entry), provenance, reason);
    }

    private void putRouteProvenance(RetrievalEntry entry, Map<String, Object> provenance) {
        Object routeIds = entry.metadata().get("originRouteIds");
        if (routeIds instanceof List<?> list) {
            provenance.put("originRouteIds", list.stream().map(String::valueOf).toList());
        } else if (entry.routeId() != null) {
            provenance.put("originRouteIds", List.of(entry.routeId().toString()));
        } else {
            provenance.put("originRouteIds", List.of());
        }
        if (entry.routeId() != null) {
            provenance.put("originRouteId", entry.routeId().toString());
        }
        if (entry.metadata().containsKey("workspaceScoped")) {
            provenance.put("workspaceScoped", Boolean.TRUE.equals(entry.metadata().get("workspaceScoped")));
        }
    }

    private RetrievalScope classifyScope(RetrievalQuery query, RetrievalEntry entry) {
        if (entry.sourceKind().name().equals("RESOURCE_CHUNK")) {
            return RetrievalScope.RESOURCE;
        }
        if (query.routeSourceRefs().contains(entry.sourceRef())
                || (query.routeId() != null && query.routeId().equals(entry.routeId()))) {
            return RetrievalScope.ROUTE;
        }
        return RetrievalScope.PROJECT;
    }

    private Map<String, Object> location(RetrievalEntry entry) {
        if (!entry.sourceKind().name().equals("RESOURCE_CHUNK")) {
            return Map.of();
        }
        return entry.metadata();
    }
}
