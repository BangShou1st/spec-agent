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
 * Host tool: bounded recent projects ordered by updated_at DESC.
 */
@Component
public class ProjectListRecentCapability implements InternalCapabilityAdapter {
    public static final String CAPABILITY_ID = "project.list_recent";
    private final GlobalProjectSearchService search;
    public ProjectListRecentCapability(GlobalProjectSearchService search) {
        this.search = search;
    }
    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "List recently updated Spec Agent projects without loading the full catalog. "
                + "Returns bounded project metadata (projectId, title, updatedAt) ordered by recency. "
                + "Use when the user asks for recent projects or when recency is the resolution signal. "
                + "Limitation: at most 10 entries; ordering is updated_at DESC with a stable tie-break.",
                Map.of("limit", Map.of("type", "integer", "required", false, "description", "Max entries (1-10, default 5)")),
                Map.of("projects", Map.of("type", "array")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        if (!GlobalAssistantToolCatalog.allowedArguments(CAPABILITY_ID)
                .containsAll(invocation.arguments().keySet())) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "Unknown argument for " + CAPABILITY_ID);
        }
        Object rawLimit = invocation.arguments().get("limit");
        if (!GlobalAssistantToolCatalog.isValidLimit(rawLimit)) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.limit must be an integer between 1 and 10");
        }
        Integer limit = rawLimit == null ? null : GlobalAssistantToolCatalog.limitOrDefault(rawLimit, 5);
        List<GlobalProjectSearchService.Candidate> recents = search.listRecent(limit);
        List<Map<String, Object>> serialized = recents.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", c.projectId().toString());
            m.put("title", c.title());
            m.put("updatedAt", c.updatedAt());
            return m;
        }).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("projects", serialized);
        content.put("totalProjects", serialized.size());
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED, content, List.of(), Map.of("kind", "PROJECT_LIST_RECENT"), List.of());
    }
}
