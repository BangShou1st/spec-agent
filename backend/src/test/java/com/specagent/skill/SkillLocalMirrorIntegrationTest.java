package com.specagent.skill;

import com.specagent.skill.registry.SkillImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Install/delete must keep the local mirror in sync with the authoritative
 * store: a fresh install projects its files to disk, a delete removes them.
 * Built-in, git and uploaded skills all share this one pipeline, so this
 * covers the unified "one local directory for every skill" behavior.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillLocalMirrorIntegrationTest {

    @TempDir
    static Path mirrorRoot;

    @Autowired
    private SkillImportService importService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.specagent.skill.config.SkillProperties skillProperties;
    @Autowired
    private com.specagent.skill.filesystem.SkillLocalMirror mirror;

    @DynamicPropertySource
    static void mirrorProperties(DynamicPropertyRegistry registry) {
        // Flat keys: `local-mirror.enabled` would not bind localMirrorEnabled.
        registry.add("spec.agent.skill.local-mirror-enabled", () -> "true");
        registry.add("spec.agent.skill.local-mirror-root", () -> mirrorRoot.toString());
    }

    private static final String SKILL_MD = """
            ---
            name: mirror-checked-skill
            description: 本地镜像一致性验证
            ---
            Step 1: 落盘
            """;

    @BeforeEach
    @AfterEach
    void clearSkillTables() {
        jdbcTemplate.update("DELETE FROM skill_activations");
        jdbcTemplate.update("DELETE FROM skill_staged_files");
        jdbcTemplate.update("DELETE FROM skill_staged_imports");
        jdbcTemplate.update("DELETE FROM skill_package_files");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM skills");
    }

    @Test
    void installMirrorsPackageFilesAndDeleteRemovesThem() throws Exception {
        System.out.println("[mirror-diag] enabled=" + skillProperties.isLocalMirrorEnabled()
                + " root=" + skillProperties.getLocalMirrorRoot()
                + " resolved=" + mirror.root());
        SkillImportService.StagedResult staged = importService.stageZip(zip(SKILL_MD));
        SkillImportService.InstalledResult installed = importService.install(staged.stagedImportId());

        Path versionDir = mirrorRoot.resolve(installed.skillId()).resolve("v1");
        assertThat(Files.readString(versionDir.resolve("SKILL.md")))
                .contains("mirror-checked-skill");

        importService.delete(installed.skillRowId());

        assertThat(Files.exists(mirrorRoot.resolve(installed.skillId()))).isFalse();
    }

    private byte[] zip(String skillMd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(out)) {
            byte[] data = skillMd.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(data);
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("SKILL.md");
            entry.setSize(data.length);
            entry.setCrc(crc.getValue());
            zip.putArchiveEntry(entry);
            zip.write(data);
            zip.closeArchiveEntry();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test zip write failed", ex);
        }
        return out.toByteArray();
    }
}
