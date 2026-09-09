package com.specagent.globalassistant.runtime;
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.project.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
/**
 * Canonical UI-action validation. Shape checks live in the decision
 * validator; existence checks live here against canonical project state.
 * Client-supplied selected entities are hints, never authorization.
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
