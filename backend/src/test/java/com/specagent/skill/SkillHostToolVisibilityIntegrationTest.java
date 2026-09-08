package com.specagent.skill;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.CapabilityDescriptor;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.capability.ResourceExtractTextCapability;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
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
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Installed != loaded" for Skill Host Function Tools: skill.* tools are
 * exposed to the planner only when the project actually has an enabled Skill
 * to activate — never as blanket-visible tooling in every context.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillHostToolVisibilityIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private static final String SKILL_MD = """
            ---
            name: doc-review-skill
            description: 文档评审流程
            ---
            Step 1: 检查文档结构
            """;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM skill_activations");
        jdbcTemplate.update("DELETE FROM skill_package_files");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM skills");
    }

    @Test
    void skillToolsAreHiddenWithoutEnabledSkills() {
        Project project = projectService.createProject("无 Skill 项目");
        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);

        assertThat(projected.availableCapabilities())
                .extracting(CapabilityDescriptor::id)
                .doesNotContain("skill.activate", "skill.read_resource");
    }

    @Test
    void skillToolsAppearWhenAnEnabledSkillExists() {
        Project project = projectService.createProject("有 Skill 项目");
        // Install + enable a Skill.
        SkillImportService.StagedResult staged = importService.stageZip(zip());
        SkillImportService.InstalledResult installed = importService.install(staged.stagedImportId());
        importService.enable(installed.skillRowId());

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);

        assertThat(projected.availableCapabilities())
                .extracting(CapabilityDescriptor::id)
                .contains("skill.activate", "skill.read_resource");
    }

    private byte[] zip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            byte[] data = SKILL_MD.getBytes(StandardCharsets.UTF_8);
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