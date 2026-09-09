package com.specagent.skill.discovery;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillVersion;

/**
 * One bounded, model-facing Skill catalog entry. The Brain only ever reads
 * these small entries — never filesystem paths, DB internals, embedding
 * scores, or full package content.
 */
public record SkillCatalogEntry(
        String skillId,
        String name,
        String description,
        String versionId,
        String contentHash,
        boolean enabled,
        String sourceKind,
        String compatibilityHint) {

    public static SkillCatalogEntry from(Skill skill, SkillVersion version) {
        return new SkillCatalogEntry(
                skill.skillId(),
                skill.name(),
                skill.description(),
                version == null ? null : version.id().toString(),
                version == null ? null : version.contentHash(),
                skill.enabled(),
                skill.sourceKind().code(),
                null);
    }

    public SkillCatalogEntry withCompatibilityHint(String hint) {
        return new SkillCatalogEntry(skillId, name, description, versionId, contentHash,
                enabled, sourceKind, hint);
    }
}