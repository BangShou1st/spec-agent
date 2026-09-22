package com.specagent.retrieval.context;

import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.retrieval.api.RetrievalQuery;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievedContextItem;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit, bounded read-only retrieval used by memory.search. */
@Service
public class RetrievalSearchService {

    private final HybridRetriever retriever;
    private final RetrievalEntryRepository entryRepository;
    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;

    public RetrievalSearchService(HybridRetriever retriever,
                                  RetrievalEntryRepository entryRepository,
                                  RouteRepository routeRepository,
                                  NodeRepository nodeRepository) {
        this.retriever = retriever;
        this.entryRepository = entryRepository;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
    }

    public SearchResult search(UUID projectId, UUID routeId, String query,
                               RetrievalScope scope, int maxResults) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), List.of());
        }
        if (scope == RetrievalScope.ROUTE) {
            validateRoute(projectId, routeId);
        }
        Set<String> routeRefs = scope == RetrievalScope.ROUTE
                ? sourceRefsForRoute(projectId, routeId) : Set.of();
        Set<UUID> graphNodes = new LinkedHashSet<>();
        if (scope == RetrievalScope.ROUTE) {
            UUID tip = routeRepository.findById(routeId).orElseThrow().tipNodeId();
            if (tip != null) {
                graphNodes.add(tip);
            }
        }
        RetrievalQuery retrievalQuery = new RetrievalQuery(
                projectId, routeId, graphNodes.stream().findFirst().orElse(null),
                "EXPLICIT_SEARCH", query,
                List.of(scope), Math.min(Math.max(maxResults, 1), 8), 8_000,
                routeRefs, graphNodes);
        List<RetrievedContextItem> items = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        int chars = 0;
        for (HybridRetriever.Candidate candidate : retriever.retrieve(retrievalQuery)) {
            RetrievedContextItem item = toItem(retrievalQuery, candidate.entry(), candidate.lanes());
            if (!refs.add(item.sourceRef())) {
                continue;
            }
            int remaining = 8_000 - chars;
            if (remaining <= 0) {
                break;
            }
            String content = item.content();
            if (content.length() > remaining) {
                item = new RetrievedContextItem(item.sourceRef(), item.sourceKind(), item.scope(),
                        item.originRouteId(), item.authority(), content.substring(0, remaining),
                        item.location(), item.provenance(), item.retrievalReason());
            }
            items.add(item);
            chars += item.content().length();
            if (items.size() >= retrievalQuery.maxItems()) {
                break;
            }
        }
        return new SearchResult(List.copyOf(items), List.copyOf(refs));
    }

    private Set<String> sourceRefsForRoute(UUID projectId, UUID routeId) {
        Set<String> refs = new LinkedHashSet<>(entryRepository.sourceRefsForRoute(projectId, routeId));
        Route route = routeRepository.findById(routeId).orElseThrow();
        UUID nodeId = route.tipNodeId();
        while (nodeId != null) {
            UUID current = nodeId;
            refs.add("node:" + current);
            nodeId = nodeRepository.findById(current).map(Node::parentNodeId).orElse(null);
        }
        return refs;
    }

    private void validateRoute(UUID projectId, UUID routeId) {
        if (routeId == null || routeRepository.findById(routeId)
                .filter(route -> route.projectId().equals(projectId)).isEmpty()) {
            throw new IllegalArgumentException("ROUTE retrieval requires a route owned by the project");
        }
    }

    private RetrievedContextItem toItem(RetrievalQuery query, RetrievalEntry entry,
                                        List<String> lanes) {
        RetrievalScope scope = entry.sourceKind().name().equals("RESOURCE_CHUNK")
                ? RetrievalScope.RESOURCE
                : query.scopes().get(0);
        UUID originRoute = scope == RetrievalScope.ROUTE ? query.routeId() : entry.routeId();
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("contentHash", entry.contentHash());
        provenance.put("sourceKind", entry.sourceKind().name());
        provenance.put("retrievalLanes", lanes);
        putRouteProvenance(entry, provenance);
        return new RetrievedContextItem(entry.sourceRef(), entry.sourceKind(), scope,
                originRoute, entry.authority(), entry.content(),
                entry.sourceKind().name().equals("RESOURCE_CHUNK") ? entry.metadata() : Map.of(),
                provenance, switch (scope) {
                    case ROUTE -> "explicit-route-search";
                    case PROJECT -> "explicit-project-search";
                    case RESOURCE -> "explicit-resource-search";
                 });
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

    public record SearchResult(List<RetrievedContextItem> items, List<String> sourceRefs) {
        public SearchResult {
            items = items == null ? List.of() : List.copyOf(items);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
