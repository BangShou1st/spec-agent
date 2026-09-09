package com.specagent.globalassistant.model;

import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fail-closed decision validation. Model output is never authorization.
 */
@Component
public class GlobalAssistantDecisionValidator {
    public void validate(GlobalAssistantDecision decision) {
        if (decision == null) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Decision must not be null");
        }
        if (decision.toolRequest() != null) {
            String capabilityId = decision.toolRequest().capabilityId();
            if (!GlobalAssistantToolCatalog.isAllowed(capabilityId)) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "Tool not in Global Assistant catalog: " + capabilityId);
            }
            validateToolArguments(capabilityId, decision.toolRequest().arguments());
        }
        if (decision.uiAction() != null) {
            validateUiAction(decision.uiAction());
        }
        if (decision.toolRequest() != null && decision.requiresUserInput()
                && decision.toolRequest() != null && decision.done() == false) {
            // clarification + tool in one decision is allowed only when the tool
            // result is needed first; terminal contract below still applies.
        }
        if (decision.toolRequest() == null && decision.uiAction() == null
                && !decision.requiresUserInput() && !decision.done()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "Decision must either request a tool, emit a UI action, require input, or finish");
        }
        if (decision.requiresUserInput() && !decision.done()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "requiresUserInput must terminalize the current run (done=true)");
        }
        if (decision.assistantText() != null && decision.assistantText().length() > 4000) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "assistantText too long");
        }
        if (decision.statusText() != null && decision.statusText().length() > 200) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "statusText too long");
        }
    }
    private void validateToolArguments(String capabilityId, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        switch (capabilityId) {
            case "project.create" -> {
                Object title = args.get("title");
                if (!(title instanceof String s) || s.isBlank() || s.trim().length() > 200) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "project.create requires a non-blank title (max 200)");
                }
            }
            case "project.search" -> {
                Object query = args.get("query");
                if (!(query instanceof String s) || s.isBlank()) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "project.search requires a non-blank query");
                }
                validateLimit(args.get("limit"));
            }
            case "project.list_recent" -> validateLimit(args.get("limit"));
            case "project.get_summary" -> {
                Object projectId = args.get("projectId");
                if (!(projectId instanceof String s) || s.isBlank()) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "project.get_summary requires a projectId UUID string");
                }
                try {
                    UUID.fromString(s.trim());
                } catch (IllegalArgumentException ex) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "project.get_summary projectId is not a valid UUID");
                }
            }
            default -> throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "Unknown tool: " + capabilityId);
        }
    }
    private void validateLimit(Object raw) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number n) || n.intValue() < 1 || n.intValue() > 10) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "limit must be an integer between 1 and 10");
        }
    }
    private void validateUiAction(GlobalAssistantDecision.UiAction uiAction) {
        if (uiAction.destination() == null) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI destination is required");
        }
        String resourceId = uiAction.resourceId();
        if (resourceId != null && !resourceId.isBlank()) {
            String trimmed = resourceId.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")
                    || trimmed.startsWith("javascript:") || trimmed.contains("://")) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "UI resourceId must be a project UUID, not a URL");
            }
            try {
                UUID.fromString(trimmed);
            } catch (IllegalArgumentException ex) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "UI resourceId must be a valid UUID when present");
            }
            if (uiAction.destination() != GlobalAssistantDecision.UiDestination.PROJECT) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "resourceId is only allowed for the PROJECT destination");
            }
        }
    }
}
