package com.specagent.assistant.tool;

import com.specagent.assistant.GlobalAssistantErrorCode;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 文件名:ProjectCreateCapability.java
 *
 * 用途:宿主工具——通过 ProjectService 创建项目。幂等性由
 * CapabilityRuntime 的 invocation_key 认领机制保证;重放绝不会
 * 创建出第二个项目。GA 工具目录中的 project.create 即本能力。
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
        if (!GlobalAssistantToolCatalog.allowedArguments(CAPABILITY_ID)
                .containsAll(invocation.arguments().keySet())) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.assistant.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "Unknown argument for " + CAPABILITY_ID);
        }
        Object rawTitle = invocation.arguments().get("title");
        if (!(rawTitle instanceof String title) || title.isBlank()) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.assistant.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.title is required and must be a non-blank string");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                    com.specagent.assistant.GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID,
                    "arguments.title must be at most 200 characters");
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
