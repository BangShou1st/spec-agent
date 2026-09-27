package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;

import com.specagent.assistant.runtime.GlobalAssistantUiActionValidator;
import com.specagent.assistant.model.GlobalAssistantModelException;
import com.specagent.workspace.project.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
/**
 * 文件名:GlobalAssistantUiActionValidator.java
 *
 * 用途:UI 动作的权威校验。形状检查归决策校验器管;
 * 存在性检查(项目是否真的存在)归这里,对着权威项目状态核对。
 * 客户端上报的选中实体只是线索,绝不构成授权。
 */
@Service
public class GlobalAssistantUiActionValidator {
    private final ProjectService projects;
    public GlobalAssistantUiActionValidator(ProjectService projects) {
        this.projects = projects;
    }
    public UUID requireExistingProject(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI resource must be a project id");
        }
        String trimmed = resourceId.trim();
        UUID projectId;
        try {
            projectId = UUID.fromString(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI resource must be a project id");
        }
        if (projects.getProject(projectId).isEmpty()) {
            throw new GlobalAssistantModelException(GlobalAssistantErrorCode.PROJECT_NOT_FOUND, "Project not found");
        }
        return projectId;
    }
    public Optional<UUID> validSelectedProject(String type, String id) {
        if (type == null || id == null || id.isBlank() || !"PROJECT".equalsIgnoreCase(type.trim())) {
            return Optional.empty();
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(id.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return projects.getProject(projectId).map(project -> project.id());
    }
}
