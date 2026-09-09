package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.common.Ids;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 acceptance: Skill discovery follows evolving Agent context.
 *
 * <p>Round one sees a broad task with a generic catalog; after a
 * capability/MCP observation lands (e.g. a database migration surfaced by a
 * tool call), a fresh continuation snapshot re-runs discovery and the newly
 * relevant Skill appears. The same frozen snapshot always replays the
 * identical catalog.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillDiscoveryEvolutionIntegrationTest {

    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private SkillImportService importService;
    @Autowired
    private CapabilityInvocationRepository invocationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String MIGRATION_SKILL_MD = """
            ---
            name: migration-safety-skill
            description: 数据库迁移安全检查流程
            ---
            Step 1: 备份
            Step 2: 验证迁移脚本
            """;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM capability_invocations");
        jdbcTemplate.update("DELETE FROM skill_activations");
        jdbcTemplate.update("DELETE FROM skill_package_files");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM skills");
    }

    @Test
    void freshContinuationDiscoversNewlyRelevantSkill() {
        Project project = projectService.createProject("演进发现项目");
        UUID runId = UUID.randomUUID();

        // Round one: broad task, no Skills installed yet — catalog is empty.
        ContextSnapshot first = contextBuilder.buildFromActiveRoute(
                project.id(), runId, ContextOperationType.NORMAL);
        AgentInputSnapshot firstProjected = snapshotBuilder.build(first);
        assertThat(firstProjected.availableSkills().skills()).isEmpty();

        // A capability observation lands (e.g. an MCP tool surfaced a
        // destructive database migration). Claim + complete it as a record
        // scoped to this project so the next fresh snapshot sees it.
        UUID invocationId = Ids.random();
        CapabilityInvocation invocation = new CapabilityInvocation(invocationId,
                "obs-key-1", "mcp.conn-x.db_migrate", project.id(), runId,
                Map.of("plan", "add column"));
        invocationRepository.claim(invocation);
        invocationRepository.complete(invocationId, new CapabilityResult(
                invocationId, "obs-key-1", "mcp.conn-x.db_migrate",
                CapabilityResult.Status.SUCCEEDED,
                Map.of("value", "migration plan: ALTER TABLE orders"),
                List.of(), Map.of("kind", "MCP_TOOL"), List.of()));

        // The migration-safety Skill gets installed before the continuation.
        SkillImportService.StagedResult staged = importService.stageZip(zip(MIGRATION_SKILL_MD));
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());

        // Round two: a fresh continuation snapshot re-runs discovery and the
        // newly relevant Skill is now visible.
        ContextSnapshot second = contextBuilder.buildFromActiveRoute(
                project.id(), runId, ContextOperationType.NORMAL);
        AgentInputSnapshot secondProjected = snapshotBuilder.build(second);
        assertThat(secondProjected.availableSkills().skills())
                .extracting(skill -> skill.name())
                .contains("migration-safety-skill");
        assertThat(secondProjected.availableSkills().fingerprint()).isNotBlank();

        // Same frozen snapshot replays the exact same catalog.
        AgentInputSnapshot replayed = snapshotBuilder.build(second);
        assertThat(replayed.availableSkills().skills())
                .isEqualTo(secondProjected.availableSkills().skills());
        assertThat(replayed.availableSkills().fingerprint())
                .isEqualTo(secondProjected.availableSkills().fingerprint());
    }

    @Test
    void skillSearchOnlyVisibleWhenCatalogTruncated() {
        Project project = projectService.createProject("截断门禁项目");
        // One small Skill: catalog is not truncated, so skill.search stays hidden.
        SkillImportService.StagedResult staged = importService.stageZip(zip(MIGRATION_SKILL_MD));
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);

        assertThat(projected.availableSkills().truncated()).isFalse();
        assertThat(projected.availableCapabilities())
                .extracting(com.specagent.agent.contract.CapabilityDescriptor::id)
                .contains("skill.activate")
                .doesNotContain("skill.search");
        assertThat(projected.availableSkills().skills()).hasSize(1);
    }

    @Test
    void largeCatalogTruncatesAndReplayStaysConsistent() {
        Project project = projectService.createProject("大目录截断项目");
        // 39 filler Skills + the migration target parked last: the automatic
        // catalog (maxVisible=24) truncates and skill.search becomes visible.
        for (int i = 0; i < 39; i++) {
            String padded = String.format("%02d", i);
            installSkill("aaa-filler-" + padded,
                    "General workspace note-taking helper number " + padded);
        }
        installSkill("zzz-postgres-migration-safety",
                "Reviews schema migrations for backwards compatibility "
                        + "and destructive changes.");

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);

        assertThat(projected.availableSkills().skills()).hasSizeLessThanOrEqualTo(24);
        assertThat(projected.availableSkills().truncated()).isTrue();
        assertThat(projected.availableSkills().skills())
                .extracting(skill -> skill.name())
                .doesNotContain("zzz-postgres-migration-safety");
        assertThat(projected.availableCapabilities())
                .extracting(com.specagent.agent.contract.CapabilityDescriptor::id)
                .contains("skill.search");

        // Same frozen snapshot replays catalog + search visibility identically.
        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed.availableSkills().skills())
                .isEqualTo(projected.availableSkills().skills());
        assertThat(replayed.availableSkills().truncated()).isTrue();
        assertThat(replayed.availableCapabilities())
                .extracting(com.specagent.agent.contract.CapabilityDescriptor::id)
                .contains("skill.search");
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
