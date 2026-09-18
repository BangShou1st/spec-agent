package com.specagent.skill.runtime;

import com.specagent.common.Hashes;
import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * On-demand Skill resource reads with strict containment and provenance.
 * Phase one serves text resources only; binary assets are refused with a
 * typed failure. Reads require an already visible/activated Skill version.
 */
@Service
public class SkillResourceService {

    private final SkillQueryService queryService;
    private final SkillProperties properties;

    public SkillResourceService(SkillQueryService queryService, SkillProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * Reads one resource inside an activated Skill version.
     *
     * @param versionId    the immutable activated version id
     * @param relativePath normalized, containment-checked resource path; SKILL.md
     *                     included, since it is shown read-only by the detail page
     * @throws SkillImportException on traversal, oversize, missing, or binary
     */
    public ResourceRead readResource(UUID versionId, String relativePath) {
        String normalized = normalizePath(relativePath);
        Optional<SkillPackageFile> file =
                queryService.findPackageFile(versionId, normalized);
        if (file.isEmpty()) {
            throw new SkillResourceRejectedException(
                    "Skill resource not found in activated version: " + normalized);
        }
        SkillPackageFile packageFile = file.get();
        if (packageFile.kind() == SkillPackageFile.FileKind.BINARY) {
            throw new SkillResourceRejectedException(
                    "Skill resource is binary; phase one serves text resources only: "
                            + normalized);
        }
        String text = packageFile.content() == null
                ? "" : new String(packageFile.content(), StandardCharsets.UTF_8);
        boolean truncated = text.length() > properties.getResourceMaxInlineBytes();
        String bounded = truncated
                ? text.substring(0, properties.getResourceMaxInlineBytes()) : text;
        return new ResourceRead(normalized, bounded, truncated, text.length(),
                packageFile.sha256(), versionId.toString());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new SkillResourceRejectedException("Skill resource path is empty");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")
                || normalized.startsWith(".") || normalized.length() > 512) {
            throw new SkillResourceRejectedException(
                    "Skill resource path rejected: " + path);
        }
        // SKILL.md is readable like any other text resource so the detail page can
        // show what the Skill actually says. Reading it never changes execution
        // semantics: running the Skill still goes through activation, which is
        // what injects the instructions.
        return normalized;
    }

    /**
     * Verifies the returned content hash matches the immutable package hash
     * (provenance self-check).
     */
    public boolean verifies(ResourceRead read) {
        return read.sha256().equals(Hashes.sha256Hex(read.content()));
    }

    public record ResourceRead(String relativePath, String content, boolean truncated,
                               int totalChars, String sha256, String versionId) {
    }
}