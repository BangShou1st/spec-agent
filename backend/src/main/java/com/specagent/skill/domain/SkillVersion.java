package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable installed Skill version. {@code contentHash} is the unique
 * content identity (canonical manifest + file hashes); a version is never
 * mutated in place — upgrades create a new version row.
 */
public record SkillVersion(
        UUID id,
        UUID skillRowId,
        int versionNo,
        String contentHash,
        String manifest,
        String instructions,
        String sourceIdentity,
        int fileCount,
        long totalBytes,
        Instant createdAt) {
}