package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One installed Skill. {@code skillId} is the stable external identity that
 * never changes across upgrades; {@code currentVersionId} points at the
 * currently installed immutable version.
 */
public record Skill(
        UUID id,
        String skillId,
        String name,
        String description,
        SkillSourceKind sourceKind,
        String sourceIdentity,
        UUID currentVersionId,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}