package com.specagent.skill;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.filesystem.SkillLocalMirror;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.domain.SkillPackageFile.FileKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * The mirror is a DB-authoritative projection: it writes bounded, contained
 * paths only, overwrites from authoritative bytes, and cleans up per skill.
 * A mirror failure must never escalate into the install pipeline.
 */
class SkillLocalMirrorTest {

    @TempDir
    Path tempDir;

    private SkillLocalMirror mirror;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setLocalMirrorEnabled(true);
        properties.setLocalMirrorRoot(tempDir.toString());
        mirror = new SkillLocalMirror(properties);
    }

    @Test
    void mirrorsPackageFilesUnderSkillVersionDirectory() throws Exception {
        mirror.mirrorVersion("sk_abc123", 1, List.of(
                new SkillSourceFile("SKILL.md", "manifest".getBytes(), FileKind.SKILL_MD),
                new SkillSourceFile("references/checklist.md", "steps".getBytes(), FileKind.TEXT)));

        Path skillDir = tempDir.resolve("sk_abc123/v1");
        assertThat(Files.readString(skillDir.resolve("SKILL.md"))).isEqualTo("manifest");
        assertThat(Files.readString(skillDir.resolve("references/checklist.md"))).isEqualTo("steps");
    }

    @Test
    void overwritesExistingMirrorFilesFromAuthoritativeBytes() throws Exception {
        mirror.mirrorVersion("sk_abc123", 2,
                List.of(new SkillSourceFile("SKILL.md", "old".getBytes(), FileKind.SKILL_MD)));
        mirror.mirrorVersion("sk_abc123", 2,
                List.of(new SkillSourceFile("SKILL.md", "new".getBytes(), FileKind.SKILL_MD)));

        assertThat(Files.readString(tempDir.resolve("sk_abc123/v2/SKILL.md"))).isEqualTo("new");
    }

    @Test
    void unsafePathsAreSwallowedAndWriteNothing() throws Exception {
        // The mirror is best-effort: an unsafe path is logged and dropped,
        // never propagated into the install pipeline, and nothing escapes.
        mirror.mirrorVersion("sk_abc123", 1,
                List.of(new SkillSourceFile("../escape.md", "x".getBytes(), FileKind.TEXT)));
        mirror.mirrorVersion("../evil", 1,
                List.of(new SkillSourceFile("SKILL.md", "x".getBytes(), FileKind.SKILL_MD)));

        assertThat(Files.exists(tempDir.resolve("escape.md"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("evil"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("sk_abc123"))).isFalse();
    }

    @Test
    void disabledMirrorWritesNothing() throws Exception {
        SkillProperties properties = new SkillProperties();
        properties.setLocalMirrorEnabled(false);
        properties.setLocalMirrorRoot(tempDir.toString());
        SkillLocalMirror disabled = new SkillLocalMirror(properties);

        disabled.mirrorVersion("sk_abc123", 1,
                List.of(new SkillSourceFile("SKILL.md", "x".getBytes(), FileKind.SKILL_MD)));

        assertThat(Files.exists(tempDir.resolve("sk_abc123"))).isFalse();
    }

    @Test
    void removeSkillDeletesTheWholeDirectory() throws Exception {
        mirror.mirrorVersion("sk_abc123", 1,
                List.of(new SkillSourceFile("SKILL.md", "x".getBytes(), FileKind.SKILL_MD)));
        assertThat(Files.exists(tempDir.resolve("sk_abc123"))).isTrue();

        mirror.removeSkill("sk_abc123");

        assertThat(Files.exists(tempDir.resolve("sk_abc123"))).isFalse();
    }
}
