package com.specagent.skill.discovery;

import java.util.List;

/**
 * Candidate metadata for a semantic retrieval search ({@code skill.search}).
 * Metadata only — never full SKILL.md content.
 */
public record SkillSearchCandidate(
        String skillId,
        String name,
        String description,
        String compatibilityHint) {
}