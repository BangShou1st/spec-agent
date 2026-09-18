package com.specagent.skill.importing;

import com.specagent.skill.domain.SkillPackageFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path rules of the Git importer, which run on repository trees that are not
 * curated the way a ZIP package is. Hidden entries are repository metadata and
 * are skipped; a repository holding many Skill directories must fail with the
 * directories it actually found rather than an unrelated containment message.
 *
 * <p>The importer is constructed with null collaborators on purpose: every rule
 * under test here is pure path/collection logic and never reaches the network
 * policy or the property bounds.
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
        // A dot inside a segment is an ordinary name, never a hidden directory.
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
