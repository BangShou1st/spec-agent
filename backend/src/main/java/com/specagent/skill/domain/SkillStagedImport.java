package com.specagent.skill.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One staged (not yet installed) Skill import awaiting review/install.
 * Staging validates, hashes, and bounds the package WITHOUT executing anything;
 * install is a separate explicit step.
 */
public record SkillStagedImport(
        UUID id,
        SkillSourceKind sourceKind,
        String sourceIdentity,
        String manifest,
        String fileEntries,
        long totalBytes,
        int fileCount,
        String contentHash,
        Status status,
        String rejectedReason,
        Instant createdAt,
        Instant installedAt) {

    public enum Status {
        STAGED,
        READY,
        INSTALLED,
        REJECTED
    }
}