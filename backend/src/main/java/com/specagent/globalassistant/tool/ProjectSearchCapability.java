package com.specagent.globalassistant.tool;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Host tool: deterministic lexical project search over title metadata.
 */
@Component
public class ProjectSearchCapability implements InternalCapabilityAdapter {
    public static final String CAPABILITY_ID = "project.search";
    private final GlobalProjectSearchService search;
    public ProjectSearchCapability(GlobalProjectSearchService search) {
        this.search = search;
    }
    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "Search existing Spec Agent projects using a short description of the project the user "
                + "is trying to locate. Returns a bounded list of candidate project metadata "
                + "(projectId, title, updatedAt). Use when the target project identity is not already "
                + "resolved from structured UI or conversation context. The search is lexical and "
                + "deterministic; semantic interpretation of the query stays with the model. "
                + "Limitation: at most 10 candidates; empty query returns no candidates.",
                Map.of("query", Map.of("type", "string", "required", true, "description", "Short description of the wanted project"),
                        "limit", Map.of("type", "integer", "required", false, "description", "Max candidates (1-10, default 5)")),
                Map.of("candidates", Map.of("type", "array")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object rawQuery = invocation.arguments().get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.query is required and must be a non-blank string");
        }
        Integer limit = readLimit(invocation.arguments().get("limit"));
        if (invocation.arguments().get("limit") != null && limit == null) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.limit must be an integer between 1 and 10");
        }
        List<GlobalProjectSearchService.Candidate> candidates = search.search(query.trim(), limit);
        List<Map<String, Object>> serialized = candidates.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", c.projectId().toString());
            m.put("title", c.title());
            m.put("updatedAt", c.updatedAt());
            return m;
        }).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("candidates", serialized);
        content.put("totalCandidates", serialized.size());
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED, content, List.of(), Map.of("kind", "PROJECT_SEARCH"), List.of());
    }
    private Integer readLimit(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v >= 1 && v <= 10 ? v : null;
        }
        return null;
    }
}
