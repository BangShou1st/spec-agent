package com.specagent.capability;

import com.specagent.retrieval.RetrievalScope;
import com.specagent.retrieval.RetrievedContextItem;
import com.specagent.retrieval.context.RetrievalSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only explicit retrieval capability; all visibility stays Runtime-owned. */
@Component
public class MemorySearchCapability implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "memory.search";

    private final RetrievalSearchService searchService;

    public MemorySearchCapability(RetrievalSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID, "1", "在当前 Workspace 的受控 Memory / Resource 范围内检索证据",
                Map.of("query", Map.of("type", "string", "required", true),
                       "scope", Map.of("type", "string", "enum", List.of("ROUTE", "PROJECT", "RESOURCE")),
                       "routeRef", Map.of("type", "string"),
                       "maxResults", Map.of("type", "integer", "maximum", 8)),
                Map.of("items", Map.of("type", "array")), true,
                // Workspace-level read-only capability: it is not tied to a
                // particular Node kind, so an ordinary Decision snapshot can
                // see it even when its contextKinds list contains no MEMORY
                // marker.
                SideEffectClass.NONE, List.of(), List.of());
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object rawQuery = invocation.arguments().get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.query must be non-blank");
        }
        String scopeText = String.valueOf(invocation.arguments().getOrDefault("scope", "PROJECT"));
        RetrievalScope scope;
        try {
            scope = RetrievalScope.valueOf(scopeText.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.scope must be ROUTE, PROJECT, or RESOURCE");
        }
        UUID routeId = null;
        Object rawRouteRef = invocation.arguments().get("routeRef");
        if (rawRouteRef instanceof String routeRef && !routeRef.isBlank()) {
            if (scope != RetrievalScope.ROUTE) {
                return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                        CAPABILITY_ID, "arguments.routeRef is only valid for ROUTE scope");
            }
            if (!routeRef.startsWith("route:")) {
                return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                        CAPABILITY_ID, "arguments.routeRef must be a route: reference");
            }
            try {
                routeId = UUID.fromString(routeRef.substring("route:".length()));
            } catch (IllegalArgumentException ex) {
                return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                        CAPABILITY_ID, "arguments.routeRef is not a valid route reference");
            }
        }
        if (scope == RetrievalScope.ROUTE && routeId == null) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "ROUTE scope requires an allowed routeRef");
        }
        int maxResults = 8;
        Object rawMax = invocation.arguments().get("maxResults");
        if (rawMax instanceof Number number) {
            maxResults = Math.min(Math.max(number.intValue(), 1), 8);
        }
        try {
            RetrievalSearchService.SearchResult result = searchService.search(
                    invocation.projectId(), routeId, query, scope, maxResults);
            List<Map<String, Object>> items = result.items().stream().map(this::wireItem).toList();
            return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, CapabilityResult.Status.SUCCEEDED,
                    Map.of("items", items, "scope", scope.name()), result.sourceRefs(),
                    Map.of("kind", "RETRIEVAL_EVIDENCE", "scope", scope.name()), List.of());
        } catch (IllegalArgumentException ex) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, ex.getMessage());
        }
    }

    private Map<String, Object> wireItem(RetrievedContextItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceRef", item.sourceRef());
        out.put("sourceKind", item.sourceKind().name());
        out.put("scope", item.scope().name());
        if (item.originRouteId() != null) {
            out.put("originRouteId", item.originRouteId().toString());
        }
        out.put("authority", item.authority().name());
        out.put("content", item.content());
        out.put("location", item.location());
        out.put("provenance", item.provenance());
        return out;
    }
}
