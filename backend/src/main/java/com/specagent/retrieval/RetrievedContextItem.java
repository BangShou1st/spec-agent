package com.specagent.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only retrieval shape exposed to the Brain. Runtime ranking details are
 * intentionally absent; source, scope, authority, and provenance remain.
 */
public record RetrievedContextItem(String sourceRef,
                                  RetrievalSourceKind sourceKind,
                                  RetrievalScope scope,
                                  UUID originRouteId,
                                  MemoryAuthority authority,
                                  String content,
                                  Map<String, Object> location,
                                  Map<String, Object> provenance,
                                  String retrievalReason) {

    public RetrievedContextItem {
        sourceRef = sourceRef == null ? "" : sourceRef;
        content = content == null ? "" : content;
        location = immutableMap(location);
        provenance = immutableMap(provenance);
        retrievalReason = retrievalReason == null ? "" : retrievalReason;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(value));
    }
}
