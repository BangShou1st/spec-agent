package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.common.Ids;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:SkillDiscoveryEvolutionIntegrationTest.java
 *
 * 测试目标:Phase 4 验收——Skill 发现跟随演化的 Agent 上下文。第一轮面对宽泛任务,
 * 目录为空;当能力/MCP 观察落地(如工具调用暴露了一次数据库迁移)后,新的续写快照重新
 * 运行发现,新相关的 Skill 出现。同一个冻结快照始终重放完全相同的目录;目录截断时
 * skill.search 才可见,且重放保持一致。
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

        // 第一轮:宽泛任务,尚未安装任何 Skill——目录为空。
        ContextSnapshot first = contextBuilder.buildFromActiveRoute(
                project.id(), runId, ContextOperationType.NORMAL);
        AgentInputSnapshot firstProjected = snapshotBuilder.build(first);
        assertThat(firstProjected.availableSkills().skills()).isEmpty();

        // 一次能力观察落地(例如 MCP 工具暴露了一次破坏性数据库迁移)。
        // 以本 project 为范围认领并完成该记录,让下一个新快照能看到它。
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

        // 在续写之前安装 migration-safety Skill。
        SkillImportService.StagedResult staged = importService.stageZip(zip(MIGRATION_SKILL_MD));
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());

        // 第二轮:新的续写快照重新运行发现,新相关的 Skill 现在可见。
        ContextSnapshot second = contextBuilder.buildFromActiveRoute(
                project.id(), runId, ContextOperationType.NORMAL);
        AgentInputSnapshot secondProjected = snapshotBuilder.build(second);
        assertThat(secondProjected.availableSkills().skills())
                .extracting(skill -> skill.name())
                .contains("migration-safety-skill");
        assertThat(secondProjected.availableSkills().fingerprint()).isNotBlank();

        // 同一冻结快照重放完全相同的目录。
        AgentInputSnapshot replayed = snapshotBuilder.build(second);
        assertThat(replayed.availableSkills().skills())
                .isEqualTo(secondProjected.availableSkills().skills());
        assertThat(replayed.availableSkills().fingerprint())
                .isEqualTo(secondProjected.availableSkills().fingerprint());
    }

    @Test
    void skillSearchOnlyVisibleWhenCatalogTruncated() {
        Project project = projectService.createProject("截断门禁项目");
        // 只有一个小 Skill:目录未截断,skill.search 保持隐藏。
        SkillImportService.StagedResult staged = importService.stageZip(zip(MIGRATION_SKILL_MD));
        SkillImportService.InstalledResult installed =
                importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);

        assertThat(projected.availableSkills().truncated()).isFalse();
        assertThat(projected.availableCapabilities())
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
                .contains("skill.activate")
                .doesNotContain("skill.search");
        assertThat(projected.availableSkills().skills()).hasSize(1);
    }

    @Test
    void largeCatalogTruncatesAndReplayStaysConsistent() {
        Project project = projectService.createProject("大目录截断项目");
        // 39 个填充 Skill + 排在最后的迁移目标:自动目录(maxVisible=24)被截断,
        // skill.search 变为可见。
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
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
                .contains("skill.search");

        // 同一冻结快照一致地重放目录与 search 可见性。
        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed.availableSkills().skills())
                .isEqualTo(projected.availableSkills().skills());
        assertThat(replayed.availableSkills().truncated()).isTrue();
        assertThat(replayed.availableCapabilities())
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
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
