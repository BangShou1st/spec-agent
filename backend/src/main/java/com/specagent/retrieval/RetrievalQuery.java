package com.specagent.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Runtime-built query; the Brain never constructs database/vector queries. */
public record RetrievalQuery(UUID projectId,
                             UUID routeId,
                             UUID anchorNodeId,
                             String operation,
                             String queryText,
                             List<RetrievalScope> scopes,
                             int maxItems,
                             int maxChars,
                             Set<String> routeSourceRefs,
                             Set<UUID> graphNodeIds) {

    public RetrievalQuery {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        queryText = queryText == null ? "" : queryText.strip();
        scopes = scopes == null || scopes.isEmpty()
                ? List.of(RetrievalScope.ROUTE, RetrievalScope.PROJECT, RetrievalScope.RESOURCE)
                : List.copyOf(scopes);
        maxItems = Math.max(0, Math.min(maxItems, 64));
        maxChars = Math.max(0, Math.min(maxChars, 50_000));
        routeSourceRefs = routeSourceRefs == null ? Set.of() : Set.copyOf(routeSourceRefs);
        graphNodeIds = graphNodeIds == null ? Set.of() : Set.copyOf(graphNodeIds);
    }
}
