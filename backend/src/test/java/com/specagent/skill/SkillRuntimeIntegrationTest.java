package com.specagent.skill;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.discovery.SkillCatalogProjector;
import com.specagent.skill.discovery.SkillCatalogEntry;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.registry.SkillImportService;
import com.specagent.skill.registry.SkillQueryService;
import com.specagent.skill.runtime.SkillActivationService;
import com.specagent.skill.runtime.SkillResourceService;
import com.specagent.skill.runtime.SkillResourceRejectedException;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:SkillRuntimeIntegrationTest.java
 *
 * 测试目标:针对真实 Postgres 存储验证 Skill 运行时的端到端集成——
 * ZIP 暂存 -> 安装 -> 启用 -> 发现 -> 激活 -> 资源读取 -> 禁用 -> 不可见,
 * 并覆盖版本与内容哈希的不可变行为以及路径越界防护。
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillRuntimeIntegrationTest {

    @Autowired
    private SkillImportService importService;
    @Autowired
    private SkillQueryService queryService;
    @Autowired
    private SkillDiscoveryService discoveryService;
    @Autowired
    private SkillActivationService activationService;
    @Autowired
    private SkillResourceService resourceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID projectId;

    private static final String SKILL_MD = """
            ---
            name: migration-safe-skill
            description: 数据库迁移安全核对流程
            references: references/checklist.md
            ---
            Step 1: 检查目标表的约束
            Step 2: 确认回滚计划
            """;

    @BeforeEach
    void createProject() {
        clearSkillTables();
        Project project = projectService.createProject("Skill 集成测试项目");
        projectId = project.id();
    }

    @AfterEach
    void cleanup() {
        // 提交式清理:已启用的 Skill 行绝不能在本测试类结束后残留,
        // 否则会改变共享此数据库的其他套件所看到的规划器能力暴露。
        clearSkillTables();
    }

    private void clearSkillTables() {
        jdbcTemplate.update("DELETE FROM skill_activations");
        jdbcTemplate.update("DELETE FROM skill_staged_files");
        jdbcTemplate.update("DELETE FROM skill_staged_imports");
        jdbcTemplate.update("DELETE FROM skill_package_files");
        jdbcTemplate.update("DELETE FROM skill_versions");
        jdbcTemplate.update("DELETE FROM skills");
    }

    @Test
    void fullSkillLifecycleWorksEndToEnd() {
        // 1. stage
        SkillImportService.StagedResult staged = importService.stageZip(
                zip(SKILL_MD, "references/checklist.md", "1. 备份完成\n2. 索引验证"));
        assertThat(staged.name()).isEqualTo("migration-safe-skill");
        assertThat(staged.fileCount()).isEqualTo(2);

        // 2. review (query) + install
        assertThat(queryService.findStagedImport(staged.stagedImportId())).isPresent();
        SkillImportService.InstalledResult installed = importService.install(staged.stagedImportId());
        assertThat(installed.skillId()).isNotNull();
        assertThat(installed.versionNo()).isEqualTo(1);

        // 版本身份不可变 + 内容哈希唯一。
        assertThat(queryService.findVersion(installed.versionId())).isPresent();
        assertThat(queryService.findVersion(installed.versionId()).orElseThrow().contentHash())
                .isEqualTo(staged.contentHash());

        // 3. not visible before enable
        SkillCatalogProjector.Projection before = discover();
        assertThat(before.entries()).isEmpty();

        // 4. enable -> visible
        importService.enable(installed.skillRowId());
        SkillCatalogProjector.Projection catalog = discover();
        assertThat(catalog.entries())
                .extracting(SkillCatalogEntry::skillId)
                .contains(installed.skillId());
        assertThat(catalog.truncated()).isFalse();

        // 5. activate -> bounded instructions + resources
        SkillActivationService.ActivatedSkill activated = activationService.activate(
                projectId, null, installed.skillId());
        assertThat(activated.instructions()).contains("检查目标表的约束");
        assertThat(activated.resources()).containsExactly("references/checklist.md");
        assertThat(activated.versionNo()).isEqualTo(1);

        // 6. read resource with provenance
        SkillResourceService.ResourceRead read = resourceService.readResource(
                installed.versionId(), "references/checklist.md");
        assertThat(read.content()).contains("备份完成");
        assertThat(read.sha256()).isNotBlank();
        assertThat(resourceService.verifies(read)).isTrue();

        // 7. traversal rejected
        assertThatThrownBy(() -> resourceService.readResource(
                installed.versionId(), "../escape"))
                .isInstanceOf(SkillResourceRejectedException.class);

        // 8. disable -> invisible
        importService.disable(installed.skillRowId());
        assertThat(discover().entries()).isEmpty();

        // 9. delete
        importService.delete(installed.skillRowId());
        assertThat(queryService.findSkill(installed.skillId())).isEmpty();
    }

    @Test
    void reinstallCreatesNewImmutableVersionOnTheSameSkillRow() {
        SkillImportService.StagedResult staged1 = importService.stageZip(
                zip(SKILL_MD, null, null));
        SkillImportService.InstalledResult installed1 =
                importService.install(staged1.stagedImportId());

        // 同名 + 同来源类型 -> 复用同一 Skill 行,版本号递增。
        SkillImportService.StagedResult staged2 = importService.stageZip(
                zip(SKILL_MD.replace("Step 1", "Step 1 revised"), null, null));
        SkillImportService.InstalledResult installed2 =
                importService.install(staged2.stagedImportId());

        assertThat(installed2.skillId()).isEqualTo(installed1.skillId());
        assertThat(installed2.skillRowId()).isEqualTo(installed1.skillRowId());
        assertThat(installed2.versionNo()).isEqualTo(2);
        assertThat(queryService.listVersions(installed2.skillRowId()))
                .extracting(com.specagent.skill.domain.SkillVersion::versionNo)
                .containsExactly(1, 2);
        // 不可变:旧版本的内容哈希保持不变。
        assertThat(queryService.findVersion(installed1.versionId())
                .orElseThrow().contentHash())
                .isEqualTo(staged1.contentHash());
        // 当前版本指针现在指向新的不可变版本。
        assertThat(queryService.findSkill(installed1.skillId())
                .orElseThrow().currentVersionId())
                .isEqualTo(installed2.versionId());

        // 新安装默认禁用;只有激活当前版本后才可见。
        importService.enable(installed1.skillRowId());
        SkillCatalogProjector.Projection catalog = discover();
        assertThat(catalog.entries())
                .extracting(SkillCatalogEntry::contentHash)
                .contains(staged2.contentHash());
    }

    private SkillCatalogProjector.Projection discover() {
        return discoveryService.discover(SkillDiscoveryContext.empty());
    }

    private byte[] zip(String skillMd, String refPath, String refContent) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            writeEntry(zip, "SKILL.md", skillMd.getBytes(StandardCharsets.UTF_8));
            if (refPath != null) {
                writeEntry(zip, refPath, refContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test zip write failed", ex);
        }
        return out.toByteArray();
    }

    private void writeEntry(ZipArchiveOutputStream zip, String path, byte[] data)
            throws java.io.IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipArchiveEntry entry = new ZipArchiveEntry(path);
        entry.setSize(data.length);
        entry.setCrc(crc.getValue());
        zip.putArchiveEntry(entry);
        zip.write(data);
        zip.closeArchiveEntry();
    }
}