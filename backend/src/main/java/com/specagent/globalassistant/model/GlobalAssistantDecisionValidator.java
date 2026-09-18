package com.specagent.globalassistant.model;

import com.specagent.globalassistant.tool.GlobalAssistantToolCatalog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fail-closed discriminated decision validation V2. Model output is never
 * authorization. Schema is the generation contract, parser is the syntactic
 * application contract, this validator is the executable safety contract.
 */
@Component
public class GlobalAssistantDecisionValidator {
    /**
     * User-visible prose must contain only complete Unicode scalar values.
     * Jackson accepts lone surrogates, so this explicit fail-closed check
     * routes them into the bounded repair path instead of the UI.
     */
    static void requireScalarValidText(String assistantText) {
        if (assistantText == null) return;
        for (int i = 0; i < assistantText.length(); ) {
            char c = assistantText.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= assistantText.length()
                        || !Character.isLowSurrogate(assistantText.charAt(i + 1))) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "assistantText contains an unpaired surrogate");
                }
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "assistantText contains an unpaired surrogate");
            } else {
                i++;
            }
        }
    }

    public void validate(GlobalAssistantDecision decision) {
        if (decision == null) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Decision must not be null");
        }
        if (decision.kind() == null) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Decision kind is required");
        }
        switch (decision.kind()) {
            case TOOL -> {
                if (decision.toolRequest() == null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "TOOL decisions require toolRequest");
                }
                if (decision.assistantText() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "TOOL decisions must not carry assistantText");
                }
                if (decision.uiAction() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "TOOL decisions must not carry a UI action");
                }
                String capabilityId = decision.toolRequest().capabilityId();
                if (!GlobalAssistantToolCatalog.isAllowed(capabilityId)) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "Tool not in Global Assistant catalog: " + capabilityId);
                }
                validateToolArguments(capabilityId, decision.toolRequest().arguments());
            }
            case CLARIFY -> {
                if (decision.toolRequest() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "CLARIFY decisions must not carry toolRequest");
                }
                if (decision.uiAction() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "CLARIFY decisions must not carry a UI action");
                }
                if (decision.assistantText() == null || decision.assistantText().isBlank()) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "CLARIFY decisions require a non-blank question");
                }
                if (decision.assistantText().length() > 4000) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "assistantText too long");
                }
                requireScalarValidText(decision.assistantText());
            }
            case NAVIGATE -> {
                if (decision.toolRequest() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "NAVIGATE decisions must not carry toolRequest");
                }
                if (decision.uiAction() == null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "NAVIGATE decisions require uiAction");
                }
                validateUiAction(decision.uiAction());
                if (decision.assistantText() != null && decision.assistantText().length() > 4000) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "assistantText too long");
                }
                requireScalarValidText(decision.assistantText());
            }
            case FINAL -> {
                if (decision.toolRequest() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "FINAL decisions must not carry toolRequest");
                }
                if (decision.uiAction() != null) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "FINAL decisions must not carry a UI action");
                }
                if (decision.assistantText() == null || decision.assistantText().isBlank()) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "FINAL decisions require non-blank assistant text");
                }
                if (decision.assistantText().length() > 4000) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "assistantText too long");
                }
                requireScalarValidText(decision.assistantText());
            }
        }
    }
    private void validateToolArguments(String capabilityId, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        java.util.Set<String> allowed = GlobalAssistantToolCatalog.allowedArguments(capabilityId);
        if (allowed != null) {
            for (String key : args.keySet()) {
                if (!allowed.contains(key)) {
                    throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                            "Unknown argument for " + capabilityId + ": " + key);
                }
            }
        }
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
            case "skill.import" -> validateSkillImportArguments(args);
            case "skill.import.discover" -> validateSkillDiscoverArguments(args);
            default -> throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "Unknown tool: " + capabilityId);
        }
    }
    private void validateLimit(Object raw) {
        if (!GlobalAssistantToolCatalog.isValidLimit(raw)) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "limit must be an integer between 1 and 10");
        }
    }
    /**
     * Bounds-only shape validation for skill.import arguments: url is a
     * required bounded string, ref/skill are optional bounded strings. The
     * value rules (HTTPS, private-host rejection, ref syntax) stay in the
     * capability/adapter, which fails as a tool execution result rather than
     * as a contract rejection — the model can correct those and retry.
     */
    private void validateSkillImportArguments(Map<String, Object> args) {
        Object url = args.get("url");
        if (!(url instanceof String s) || s.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "skill.import requires a non-blank url");
        }
        if (s.trim().length() > 500) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "skill.import url must be at most 500 chars");
        }
        requireOptionalBoundedString(args, "ref", 200, "skill.import ref");
        requireOptionalBoundedString(args, "skill", 512, "skill.import skill");
    }
    /**
     * Bounds-only shape validation for skill.import.discover arguments: url is
     * a required bounded string, ref is an optional bounded string. Same
     * policy as skill.import: value rules (HTTPS, private-host rejection)
     * stay in the capability and fail as tool execution results.
     */
    private void validateSkillDiscoverArguments(Map<String, Object> args) {
        Object url = args.get("url");
        if (!(url instanceof String s) || s.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "skill.import.discover requires a non-blank url");
        }
        if (s.trim().length() > 500) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "skill.import.discover url must be at most 500 chars");
        }
        requireOptionalBoundedString(args, "ref", 200, "skill.import.discover ref");
    }
    private void requireOptionalBoundedString(Map<String, Object> args, String key,
            int maxChars, String label) {
        Object raw = args.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    label + " must be a non-blank string when present");
        }
        if (s.trim().length() > maxChars) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    label + " must be at most " + maxChars + " chars");
        }
    }
    private void validateUiAction(GlobalAssistantDecision.UiAction uiAction) {
        if (uiAction.destination() == null) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "UI destination is required");
        }
        String resourceId = uiAction.resourceId();
        if (uiAction.destination() == GlobalAssistantDecision.UiDestination.PROJECT) {
            if (resourceId == null || resourceId.isBlank()) {
                throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                        "PROJECT navigation requires a resourceId");
            }
        } else if (resourceId != null && !resourceId.isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE",
                    "resourceId is only allowed for the PROJECT destination");
        }
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
