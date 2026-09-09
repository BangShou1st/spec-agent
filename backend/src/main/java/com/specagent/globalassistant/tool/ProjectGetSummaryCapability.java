package com.specagent.globalassistant.tool;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Host tool: bounded application-level project summary. Never dumps the graph.
 */
@Component
public class ProjectGetSummaryCapability implements InternalCapabilityAdapter {
    public static final String CAPABILITY_ID = "project.get_summary";
    private final GlobalProjectSummaryQueryService summaries;
    public ProjectGetSummaryCapability(GlobalProjectSummaryQueryService summaries) {
        this.summaries = summaries;
    }
    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "Read a bounded application-level summary of one project (title, activity, route/node counts, "
                + "active route identity, spec availability). Returns typed stable fields only, never the full "
                + "requirement graph. Use when the user asks about a resolved project's current state. "
                + "Limitation: projectId must be a valid UUID of an existing project.",
                Map.of("projectId", Map.of("type", "string", "required", true, "description", "Target project UUID")),
                Map.of("projectId", Map.of("type", "string"), "title", Map.of("type", "string")),
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
        Object rawId = invocation.arguments().get("projectId");
        if (!(rawId instanceof String text) || text.isBlank()) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.projectId is required and must be a project UUID string");
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(text.trim());
        } catch (IllegalArgumentException ex) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.projectId is not a valid UUID");
        }
        Optional<Map<String, Object>> summary = summaries.summarize(projectId);
        if (summary.isEmpty()) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.globalassistant.runtime.GlobalAssistantErrorCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectId);
        }
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED, summary.get(),
                List.of("project:" + projectId), Map.of("kind", "PROJECT_SUMMARY"), List.of());
    }
}
