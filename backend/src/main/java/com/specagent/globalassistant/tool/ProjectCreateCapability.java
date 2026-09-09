package com.specagent.globalassistant.tool;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Host tool: create a project via ProjectService. Idempotent through the
 * CapabilityRuntime invocation_key claim; replay never creates a second project.
 */
@Component
public class ProjectCreateCapability implements InternalCapabilityAdapter {
    public static final String CAPABILITY_ID = "project.create";
    private final ProjectService projects;
    public ProjectCreateCapability(ProjectService projects) {
        this.projects = projects;
    }
    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "Create a new Spec Agent project with the given title. Returns the new project identity "
                + "including projectId, title and activeRouteId. Use when the user wants a new project "
                + "to be created. This is a durable local operation; the Runtime owns idempotency via "
                + "the invocation key, so retries never create duplicates. Result carries projectId, title "
                + "and activeRouteId. Limitation: title is required and must be non-blank.",
                Map.of("title", Map.of("type", "string", "required", true, "description", "Project title")),
                Map.of("projectId", Map.of("type", "string"), "title", Map.of("type", "string"),
                        "activeRouteId", Map.of("type", "string")),
                false,
                SideEffectClass.LOCAL_DURABLE,
                List.of(),
                List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object rawTitle = invocation.arguments().get("title");
        if (!(rawTitle instanceof String title) || title.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.title is required and must be a non-blank string");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.title must be at most 200 characters");
        }
        Project created = projects.createProject(trimmed);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("projectId", created.id().toString());
        content.put("title", created.title());
        if (created.activeRouteId() != null) {
            content.put("activeRouteId", created.activeRouteId().toString());
        }
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED, content, List.of("project:" + created.id()),
                Map.of("kind", "PROJECT_CREATED"), List.of());
    }
}
