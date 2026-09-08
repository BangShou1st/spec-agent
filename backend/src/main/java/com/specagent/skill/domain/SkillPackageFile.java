package com.specagent.skill.domain;

import java.util.UUID;

/**
 * One immutable file inside an installed Skill version. {@code relativePath}
 * is normalized and containment-checked at import time; {@code content} is
 * text for TEXT-ish kinds (base64 kept out of the domain object — the
 * repository stores bytes).
 */
public record SkillPackageFile(
        UUID id,
        UUID versionId,
        String relativePath,
        FileKind kind,
        long sizeBytes,
        String sha256,
        byte[] content) {

    public enum FileKind {
        SKILL_MD("SKILL_MD"),
        TEXT("TEXT"),
        BINARY("BINARY");

        private final String code;

        FileKind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static FileKind fromCode(String code) {
            for (FileKind kind : values()) {
                if (kind.code.equals(code)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown skill file kind: " + code);
        }
    }
}