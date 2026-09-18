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
import java.util.Locale;
import java.util.Map;

/**
 * Host tool: stage a Skill import from an HTTPS git repository.
 *
 * <p><b>Staging only.</b> The package is cloned, validated and stored as a
 * reviewable staging row — never executed, never installed, never enabled.
 * Installation stays a user action in the Skills settings page, and an
 * installed Skill remains disabled until it is enabled there. That ordering is
 * what makes it safe to let the model start an import: the assistant can only
 * put a candidate in front of the user, not make it agent-visible.
 *
 * <p>Repositories that hold a single Skill are staged directly. Repositories
 * that hold a library (a {@code skills/<name>/} tree or a plugin marketplace)
 * come back as candidates so the assistant asks which one to use instead of
 * guessing.
 */
@Component
public class SkillImportCapability implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "skill.import";

    /** Candidate lists are bounded so one marketplace cannot flood the model. */
    private static final int MAX_REPORTED_CANDIDATES = 20;

    /**
     * Candidate metadata comes from an untrusted repository, so it enters the
     * model context only in bounded, display-sized form.
     */
    private static final int MAX_CANDIDATE_DESCRIPTION_CHARS = 160;

    private static final int MAX_URL_CHARS = 500;
    private static final int MAX_REF_CHARS = 200;
    private static final int MAX_SKILL_CHARS = 512;

    private final SkillImportService imports;

    public SkillImportCapability(SkillImportService imports) {
        this.imports = imports;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "Stage a Skill import from an HTTPS git repository so the user can review and "
                + "install it in the Skills settings page. Use when the user wants to install, "
                + "add or import a Skill from a git URL. The repository is inspected first: a "
                + "repository holding exactly one Skill is staged immediately; a Skills library "
                + "or plugin marketplace returns its candidate Skill directories so you can ask "
                + "which one is wanted, or pass skill to choose one directly. Staging never runs "
                + "package content, never installs and never enables anything: after a successful "
                + "staging, tell the user to open the Skills settings page to review and install "
                + "it. Result carries "
                + "stagedImportId, name, skillPath, and requiresChoice with the candidate list "
                + "when the user still has to pick one. Limitation: HTTPS git URLs only; ZIP "
                + "uploads stay a user action.",
                Map.of(
                        "url", Map.of("type", "string", "required", true,
                                "description", "HTTPS git repository URL"),
                        "ref", Map.of("type", "string", "required", false,
                                "description", "Branch, tag or commit to pin; omit for the default branch"),
                        "skill", Map.of("type", "string", "required", false,
                                "description", "Skill directory or Skill name inside the repository, "
                                + "for example skills/brainstorming")),
                Map.of(
                        "stagedImportId", Map.of("type", "string"),
                        "name", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "skillPath", Map.of("type", "string"),
                        "fileCount", Map.of("type", "integer"),
                        "requiresChoice", Map.of("type", "boolean"),
                        "candidateCount", Map.of("type", "integer"),
                        "candidates", Map.of("type", "array")),
                false,
                SideEffectClass.LOCAL_DURABLE,
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
        String requested = text(arguments, "skill");
        if (requested != null && requested.length() > MAX_SKILL_CHARS) {
            return reject(invocation,
                    "arguments.skill must be at most " + MAX_SKILL_CHARS + " chars");
        }

        SkillImportService.DiscoveryResult discovery;
        try {
            discovery = imports.discoverGit(url, ref);
        } catch (SkillImportException ex) {
            return failure(invocation, ex.getMessage());
        }

        List<SkillImportService.DiscoveryCandidate> usable = discovery.candidates().stream()
                .filter(SkillImportService.DiscoveryCandidate::parseable)
                .toList();
        if (usable.isEmpty()) {
            return failure(invocation, "No Skill package (SKILL.md) was found in that repository");
        }

        List<SkillImportService.DiscoveryCandidate> matches = match(usable, requested);
        if (matches.isEmpty()) {
            return reject(invocation, "arguments.skill matched no Skill in the repository."
                    + " Available: " + summary(usable, MAX_REPORTED_CANDIDATES));
        }
        if (matches.size() > 1) {
            // Ambiguity is a question for the user, not a guess: report the
            // candidates and stage nothing.
            return choiceResult(invocation, discovery, matches, requested != null);
        }

        SkillImportService.DiscoveryCandidate selected = matches.get(0);
        SkillImportService.StagedResult staged;
        try {
            staged = imports.stageGit(url, ref, selected.path());
        } catch (SkillImportException ex) {
            return failure(invocation, ex.getMessage());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("stagedImportId", staged.stagedImportId().toString());
        content.put("name", staged.name());
        if (staged.description() != null) {
            content.put("description", staged.description());
        }
        content.put("skillPath", selected.path());
        content.put("fileCount", staged.fileCount());
        content.put("requiresChoice", false);
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                CAPABILITY_ID, CapabilityResult.Status.SUCCEEDED, content,
                List.of("skill_import:" + staged.stagedImportId()),
                Map.of("kind", "SKILL_IMPORT_STAGED"), List.of());
    }

    private CapabilityResult choiceResult(CapabilityInvocation invocation,
                                          SkillImportService.DiscoveryResult discovery,
                                          List<SkillImportService.DiscoveryCandidate> matches,
                                          boolean requestedSkill) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SkillImportService.DiscoveryCandidate candidate
                : matches.stream().limit(MAX_REPORTED_CANDIDATES).toList()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", candidate.path());
            entry.put("name", candidate.name());
            entry.put("description", bounded(candidate.description()));
            candidates.add(entry);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requiresChoice", true);
        // No stagedImportId key at all: absence is the honest signal that
        // nothing was staged yet (content maps reject null values).
        if (!requestedSkill && discovery.suggestedPath() != null) {
            content.put("skillPath", discovery.suggestedPath());
        }
        content.put("candidateCount", matches.size());
        content.put("candidates", candidates);
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                CAPABILITY_ID, CapabilityResult.Status.SUCCEEDED, content, List.of(),
                Map.of("kind", "SKILL_IMPORT_CHOICE_REQUIRED"), List.of());
    }

    private List<SkillImportService.DiscoveryCandidate> match(
            List<SkillImportService.DiscoveryCandidate> usable, String requested) {
        if (requested == null) {
            return usable;
        }
        String needle = requested.strip().toLowerCase(Locale.ROOT);
        List<SkillImportService.DiscoveryCandidate> byPath = usable.stream()
                .filter(c -> c.path().toLowerCase(Locale.ROOT).equals(needle)
                        || c.path().toLowerCase(Locale.ROOT).endsWith("/" + needle))
                .toList();
        if (!byPath.isEmpty()) {
            return byPath;
        }
        return usable.stream()
                .filter(c -> c.name().equalsIgnoreCase(needle))
                .toList();
    }

    private String summary(List<SkillImportService.DiscoveryCandidate> candidates, int limit) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (SkillImportService.DiscoveryCandidate candidate : candidates) {
            if (count >= limit) {
                builder.append(", …");
                break;
            }
            if (count > 0) {
                builder.append(", ");
            }
            builder.append(candidate.path());
            count++;
        }
        return builder.toString();
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
                message == null ? "Skill import failed" : message);
    }
}
