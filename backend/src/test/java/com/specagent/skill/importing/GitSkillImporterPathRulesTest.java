package com.specagent.skill.importing;

import com.specagent.skill.domain.SkillPackageFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:GitSkillImporterPathRulesTest.java
 *
 * 测试目标:验证 Git 导入器的路径规则。它处理的仓库树不像 ZIP 包那样
 * 经过人工筛选:隐藏条目属于仓库元数据,应被跳过;包含多个 Skill 目录的
 * 仓库报错时,必须列出实际找到的目录,而不是抛出无关的越界提示。
 *
 * 这里故意用 null 协作对象构造导入器:被测的每条规则都是纯粹的
 * 路径/集合逻辑,不会触达网络策略或属性上限。
 */
class GitSkillImporterPathRulesTest {

    private final GitSkillImporter importer = new GitSkillImporter(null, null);

    @Test
    void hiddenEntriesAreMetadataNotSkillContent() {
        assertThat(GitSkillImporter.isHiddenEntry(".git/config")).isTrue();
        assertThat(GitSkillImporter.isHiddenEntry(".github/workflows/ci.yml")).isTrue();
        assertThat(GitSkillImporter.isHiddenEntry(".agents/plugins/marketplace.json")).isTrue();
        assertThat(GitSkillImporter.isHiddenEntry(".claude-plugin/marketplace.json")).isTrue();
        assertThat(GitSkillImporter.isHiddenEntry("skills/a/.hidden/notes.md")).isTrue();
        assertThat(GitSkillImporter.isHiddenEntry(".gitignore")).isTrue();
    }

    @Test
    void onlyDotPrefixedSegmentsAreHidden() {
        assertThat(GitSkillImporter.isHiddenEntry("SKILL.md")).isFalse();
        assertThat(GitSkillImporter.isHiddenEntry("scripts/run.sh")).isFalse();
        // 路径段内部的点是普通文件名的一部分,绝不构成隐藏目录。
        assertThat(GitSkillImporter.isHiddenEntry("skills/v1.2/SKILL.md")).isFalse();
        assertThat(GitSkillImporter.isHiddenEntry("references/a.b.md")).isFalse();
    }

    @Test
    void marketplaceManifestsAreReadButNeverPackaged() {
        assertThat(GitSkillImporter.dispositionOf(".claude-plugin/marketplace.json"))
                .isEqualTo(GitSkillImporter.EntryDisposition.MANIFEST);
        assertThat(GitSkillImporter.dispositionOf(".agents/plugins/marketplace.json"))
                .isEqualTo(GitSkillImporter.EntryDisposition.MANIFEST);
        assertThat(GitSkillImporter.dispositionOf("SKILL.md"))
                .isEqualTo(GitSkillImporter.EntryDisposition.PACKAGE);
        assertThat(GitSkillImporter.dispositionOf("skills/a/SKILL.md"))
                .isEqualTo(GitSkillImporter.EntryDisposition.PACKAGE);
        assertThat(GitSkillImporter.dispositionOf(".claude-plugin/plugin.json"))
                .isEqualTo(GitSkillImporter.EntryDisposition.SKIP);
        assertThat(GitSkillImporter.dispositionOf(".git/config"))
                .isEqualTo(GitSkillImporter.EntryDisposition.SKIP);
    }

    @Test
    void normalizeKeepsContainedPathsAndRejectsRealEscapes() {
        assertThat(importer.normalizePath("references/a.md")).isEqualTo("references/a.md");
        assertThat(importer.normalizePath("references\\a.md")).isEqualTo("references/a.md");
        assertThatThrownBy(() -> importer.normalizePath("/etc/passwd"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("escapes the package root");
        assertThatThrownBy(() -> importer.normalizePath("../outside.md"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("escapes the package root");
    }

    @Test
    void rootSkillMarkdownIsPreferred() {
        List<SkillSourceFile> files = List.of(
                file("SKILL.md"),
                file("references/a.md"));

        assertThat(importer.requireRootSkillMarkdown(files).relativePath())
                .isEqualTo("SKILL.md");
    }

    @Test
    void multiSkillRepositoryNamesTheSkillDirectoriesItFound() {
        List<SkillSourceFile> files = List.of(
                file("README.md"),
                file("skills/brainstorming/SKILL.md"),
                file("skills/testing/SKILL.md"));

        assertThatThrownBy(() -> importer.requireRootSkillMarkdown(files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("not a single Skill package")
                .hasMessageContaining("2 Skill directories")
                .hasMessageContaining("Import one of them")
                .hasMessageContaining("skills/brainstorming")
                .hasMessageContaining("skills/testing");
    }

    @Test
    void aSelectedDirectoryWithoutSkillMarkdownIsReportedAsSuch() {
        List<SkillSourceFile> catalogue = List.of(
                file("README.md"),
                file("skills/brainstorming/SKILL.md"));
        List<SkillSourceFile> selected = List.of(file("README.md"));

        assertThatThrownBy(() -> importer.requireRootSkillMarkdown(
                selected, catalogue, "docs/notes"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("Selected directory is not a Skill package")
                .hasMessageContaining("no SKILL.md in docs/notes")
                .hasMessageContaining("skills/brainstorming");
    }

    @Test
    void oversizedSkillDirectoryListIsTruncatedInTheMessage() {
        List<SkillSourceFile> files = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            files.add(file("skills/s" + i + "/SKILL.md"));
        }

        assertThatThrownBy(() -> importer.requireRootSkillMarkdown(files))
                .hasMessageContaining("7 Skill directories")
                .hasMessageContaining("skills/s5")
                .hasMessageNotContaining("skills/s6");
    }

    @Test
    void packageWithoutAnySkillMarkdownKeepsThePlainFailure() {
        List<SkillSourceFile> files = List.of(file("README.md"), file("docs/a.md"));

        assertThatThrownBy(() -> importer.requireRootSkillMarkdown(files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("missing SKILL.md at the root");
    }

    private static SkillSourceFile file(String path) {
        byte[] content = path.getBytes(StandardCharsets.UTF_8);
        return new SkillSourceFile(path, content, SkillPackageFile.FileKind.TEXT);
    }
}
