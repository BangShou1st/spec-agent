package com.specagent.skill.domain;

import java.util.List;
import java.util.Map;

/**
 * Parsed runtime metadata of a Skill package. The open Agent Skills
 * {@code SKILL.md} YAML front-matter is the package format authority; only
 * {@code name} and {@code description} are mandatory. Additional runtime
 * metadata (skillId, source, hashes, enabled state) is owned by the runtime
 * store, never by the package format.
 */
public record SkillManifest(
        String name,
        String description,
        String instructions,
        Map<String, String> extraMetadata,
        List<String> references) {

    public static final String MINIMAL_NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$";

    public SkillManifest {
        extraMetadata = extraMetadata == null ? Map.of() : Map.copyOf(extraMetadata);
        references = references == null ? List.of() : List.copyOf(references);
    }

    public boolean hasValidName() {
        return name != null && name.matches(MINIMAL_NAME_PATTERN);
    }
}