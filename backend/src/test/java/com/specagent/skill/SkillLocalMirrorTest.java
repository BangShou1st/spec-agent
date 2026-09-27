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
 * 文件名:SkillLocalMirrorTest.java
 *
 * 测试目标:验证本地镜像作为"数据库为权威来源"的投影——只写入有界、
 * 受控的路径,用权威字节覆盖旧内容,并按 Skill 粒度清理。镜像失败绝不能
 * 升级为安装管线的失败。
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
        // 镜像是尽力而为的:不安全路径只会被记录并丢弃,不会传播进
        // 安装管线,也不会有任何内容逃逸出根目录。
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
