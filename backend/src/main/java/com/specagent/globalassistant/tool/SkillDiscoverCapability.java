package com.specagent.globalassistant.tool;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import com.specagent.globalassistant.runtime.GlobalAssistantErrorCode;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillImportService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host tool: read-only discovery of the Skill packages an HTTPS git
 * repository actually contains.
 *
 * <p>This is the model's only trustworthy source for skill names, counts and
 * descriptions: every candidate comes from the repository's SKILL.md
 * manifests, never from model memory. It stages nothing — staging stays with
 * {@link SkillImportCapability}, so discovery can be called freely to ground
 * "which skills does this repo have" before any install decision.
 */
@Component
public class SkillDiscoverCapability implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "skill.import.discover";

    /** Candidate lists are bounded so one marketplace cannot flood the model. */
    private static final int MAX_REPORTED_CANDIDATES = 20;

    /**
     * Candidate metadata comes from an untrusted repository, so it enters the
     * model context only in bounded, display-sized form.
     */
    private static final int MAX_CANDIDATE_DESCRIPTION_CHARS = 160;

    private static final int MAX_URL_CHARS = 500;
    private static final int MAX_REF_CHARS = 200;

    private final SkillImportService imports;

    public SkillDiscoverCapability(SkillImportService imports) {
        this.imports = imports;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "List the Skill packages an HTTPS git repository actually contains, without staging "
                + "or importing anything. Use this whenever the user asks which Skills a repository "
                + "offers, what can be installed from it, or which Skill to pick: skill names, "
                + "counts and descriptions must come from this tool result, never from memory. "
                + "Returns commitSha, candidateCount, suggestedPath and a candidates list of "
                + "path/name/description. After the user picks one, call skill.import with that "
                + "path as the skill argument. Limitation: HTTPS git URLs only.",
                Map.of(
                        "url", Map.of("type", "string", "required", true,
                                "description", "HTTPS git repository URL"),
                        "ref", Map.of("type", "string", "required", false,
                                "description", "Branch, tag or commit to pin; omit for the default branch")),
                Map.of(
                        "url", Map.of("type", "string"),
                        "ref", Map.of("type", "string"),
                        "commitSha", Map.of("type", "string"),
                        "candidateCount", Map.of("type", "integer"),
                        "suggestedPath", Map.of("type", "string"),
                        "candidates", Map.of("type", "array")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        for (String key : arguments.keySet()) {
            if (!GlobalAssistantToolCatalog.allowedArguments(CAPABILITY_ID).contains(key)) {
                return reject(invocation, "Unknown argument for " + CAPABILITY_ID + ": " + key);
            }
        }
        String url = text(arguments, "url");
        if (url == null) {
            return reject(invocation,
                    "arguments.url is required and must be a non-blank HTTPS URL");
        }
        if (url.length() > MAX_URL_CHARS) {
            return reject(invocation, "arguments.url must be at most " + MAX_URL_CHARS + " chars");
        }
        String ref = text(arguments, "ref");
        if (ref != null && ref.length() > MAX_REF_CHARS) {
            return reject(invocation, "arguments.ref must be at most " + MAX_REF_CHARS + " chars");
        }

        SkillImportService.DiscoveryResult discovery;
        try {
            discovery = imports.discoverGit(url, ref);
        } catch (SkillImportException ex) {
            return failure(invocation, ex.getMessage());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("url", url);
        if (ref != null) {
            content.put("ref", ref);
        }
        if (discovery.commitSha() != null) {
            content.put("commitSha", discovery.commitSha());
        }
        content.put("candidateCount", discovery.candidates().size());
        if (discovery.suggestedPath() != null) {
            content.put("suggestedPath", discovery.suggestedPath());
        }
        content.put("candidates", candidates(discovery.candidates()));
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                CAPABILITY_ID, CapabilityResult.Status.SUCCEEDED, content, List.of(),
                Map.of("kind", "SKILL_DISCOVERY"), List.of());
    }

    private List<Map<String, Object>> candidates(List<SkillImportService.DiscoveryCandidate> all) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SkillImportService.DiscoveryCandidate candidate : all) {
            if (candidates.size() >= MAX_REPORTED_CANDIDATES) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", candidate.path());
            entry.put("name", candidate.name());
            entry.put("description", bounded(candidate.description()));
            entry.put("parseable", candidate.parseable());
            candidates.add(entry);
        }
        return candidates;
    }

    private String bounded(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= MAX_CANDIDATE_DESCRIPTION_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_CANDIDATE_DESCRIPTION_CHARS) + "…";
    }

    private String text(Map<String, Object> arguments, String key) {
        Object raw = arguments.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private CapabilityResult reject(CapabilityInvocation invocation, String message) {
        return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                GlobalAssistantErrorCode.TOOL_ARGUMENT_INVALID, message);
    }

    private CapabilityResult failure(CapabilityInvocation invocation, String message) {
        return GlobalAssistantToolFailures.failed(invocation, CAPABILITY_ID,
                GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                message == null ? "Skill discovery failed" : message);
    }
}
