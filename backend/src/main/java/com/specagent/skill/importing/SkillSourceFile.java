package com.specagent.skill.importing;

import com.specagent.skill.domain.SkillPackageFile;

/**
 * One validated, in-memory file extracted from a Skill package source (ZIP
 * archive or git tree). Content is held in memory and later persisted as an
 * immutable package row — never written to the host filesystem.
 */
public record SkillSourceFile(String relativePath, byte[] content,
                              SkillPackageFile.FileKind kind) {

    public SkillSourceFile {
        content = content == null ? new byte[0] : content;
    }
}