package com.specagent.skill.runtime;

import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.common.Ids;
import com.specagent.skill.registry.SkillImportService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end fallback loop against the real discovery stack (40 enabled
 * Skills, maxVisible=24): the automatic catalog truncates and hides the
 * migration target; {@code skill.search(query)} recalls it from the full
 * eligible universe with metadata only and without activating anything.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillSearchFallbackIntegrationTest {

    @Autowired
    private SkillImportService importService;
    @Autowired
    private SkillSearchHostTool searchTool;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM skill_activations");
        jdbcTemplate.update("DELETE FROM skill_package_files");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM skills");
    }

    @Test
    void searchRecallsHiddenTargetWithMetadataOnlyAndNoActivation() {
        for (int i = 0; i < 39; i++) {
            String padded = String.format("%02d", i);
            installSkill("aaa-filler-" + padded,
                    "General workspace note-taking helper number " + padded);
        }
        installSkill("zzz-postgres-migration-safety",
                "Reviews schema migrations for backwards compatibility "
                        + "and destructive changes.");

        // Case B: the target is recalled from outside the automatic Top-K.
        CapabilityResult result = search("schema migration backwards compatibility");
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        List<?> candidates = (List<?>) result.content().get("candidates");
        assertThat(candidates.stream().map(Object::toString).toList().toString())
                .contains("zzz-postgres-migration-safety");

        // Case C: paraphrase without exact wording still recalls it.
        CapabilityResult paraphrased =
                search("review backwards-compatible schema migrations");
        assertThat(((List<?>) paraphrased.content().get("candidates"))
                .stream().map(Object::toString).toList().toString())
                .contains("zzz-postgres-migration-safety");

        // Case F: metadata only — no bodies, paths, or internals.
        String wire = result.content().toString();
        assertThat(wire).doesNotContain("SKILL.md")
                .doesNotContain("filesystem")
                .doesNotContain("embedding");

        // Case G: search never auto-activates.
        Integer activations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_activations", Integer.class);
        assertThat(activations).isZero();
    }

    private CapabilityResult search(String query) {
        return searchTool.invoke(new CapabilityInvocation(Ids.random(),
                "search-" + UUID.randomUUID(), SkillSearchHostTool.CAPABILITY_ID,
                UUID.randomUUID(), UUID.randomUUID(), Map.of("query", query)));
    }

    private void installSkill(String name, String description) {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\nBody\n";
        SkillImportService.StagedResult staged = importService.stageZip(zip(md));
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());
    }

    private byte[] zip(String skillMd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            byte[] data = skillMd.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(data);
            ZipArchiveEntry entry = new ZipArchiveEntry("SKILL.md");
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
