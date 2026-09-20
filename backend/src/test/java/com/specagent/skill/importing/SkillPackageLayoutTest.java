package com.specagent.skill.importing;

import com.specagent.skill.domain.SkillPackageFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layout rules that decide what counts as a Skill package inside a repository.
 * Every case here is offline and pure: discovery navigates real-world
 * repository shapes (a Skills library, a plugin marketplace) without loosening
 * any containment rule.
 */
class SkillPackageLayoutTest {

    // ---- discovery --------------------------------------------------------

    @Test
    void rootPackageComesFirstAndNestedPackagesFollowByName() {
        List<SkillPackageLayout.SkillRoot> roots = SkillPackageLayout.discover(List.of(
                "README.md",
                "SKILL.md",
                "skills/testing/SKILL.md",
                "skills/brainstorming/SKILL.md"), List.of());

        assertThat(roots).hasSize(3);
        assertThat(roots.get(0).path()).isEmpty();
        assertThat(roots.get(0).kind()).isEqualTo(SkillPackageLayout.Kind.ROOT);
        assertThat(roots.get(0).displayPath()).isEqualTo("SKILL.md");
        assertThat(roots.get(1).path()).isEqualTo("skills/brainstorming");
        assertThat(roots.get(2).path()).isEqualTo("skills/testing");
    }

    @Test
    void nestedPackagesAreDedupedAndIgnoreSkillMarkdownLookalikes() {
        List<SkillPackageLayout.SkillRoot> roots = SkillPackageLayout.discover(List.of(
                "skills/a/SKILL.md",
                "skills/a/SKILL.md",
                "skills/a/references/SKILL.md.bak",
                "skills/a/NOTES.md",
                "skills/SKILL.md"), List.of());

        assertThat(roots).extracting(SkillPackageLayout.SkillRoot::path)
                .containsExactly("skills", "skills/a");
    }

    @Test
    void marketplaceDeclaredPluginsAttributeTheirOwnSkillDirectories() {
        // Same payload shape as obra/superpowers/.claude-plugin/marketplace.json
        Map<String, Object> marketplace = Map.of(
                "name", "superpowers-dev",
                "plugins", List.of(Map.of(
                        "name", "superpowers",
                        "version", "6.3.0",
                        "source", "./")));
        List<String> declared = SkillPackageLayout.declaredPluginSources(marketplace);
        assertThat(declared).containsExactly("");

        List<SkillPackageLayout.SkillRoot> roots = SkillPackageLayout.discover(List.of(
                "skills/brainstorming/SKILL.md",
                "docs/notes/SKILL.md"), declared);

        // A plugin whose source is the repository root owns every Skill below it.
        assertThat(roots).extracting(SkillPackageLayout.SkillRoot::kind)
                .containsOnly(SkillPackageLayout.Kind.MARKETPLACE);
        assertThat(roots.get(0).declaredBy()).isEmpty();
        assertThat(roots.get(0).kind()).isEqualTo(SkillPackageLayout.Kind.MARKETPLACE);
    }

    @Test
    void nestedPluginSourceOnlyCoversItsOwnSubtree() {
        Map<String, Object> marketplace = Map.of(
                "plugins", List.of(Map.of("name", "p", "source", "./plugins/foo")));

        List<SkillPackageLayout.SkillRoot> roots = SkillPackageLayout.discover(List.of(
                "plugins/foo/skills/bar/SKILL.md",
                "other/skills/baz/SKILL.md"),
                SkillPackageLayout.declaredPluginSources(marketplace));

        SkillPackageLayout.SkillRoot declaredRoot = rootAt(roots, "plugins/foo/skills/bar");
        SkillPackageLayout.SkillRoot otherRoot = rootAt(roots, "other/skills/baz");
        assertThat(declaredRoot.kind()).isEqualTo(SkillPackageLayout.Kind.MARKETPLACE);
        assertThat(declaredRoot.declaredBy()).isEqualTo("plugins/foo");
        assertThat(otherRoot.kind()).isEqualTo(SkillPackageLayout.Kind.NESTED);
        assertThat(otherRoot.declaredBy()).isNull();
    }

    @Test
    void untrustedMarketplaceSourcesAreIgnored() {
        Map<String, Object> marketplace = Map.of(
                "plugins", List.of(
                        Map.of("name", "abs", "source", "/etc"),
                        Map.of("name", "url", "source", "https://evil.example/x"),
                        Map.of("name", "traversal", "source", "../../outside"),
                        Map.of("name", "hidden", "source", "./.git/hooks"),
                        Map.of("name", "ok", "source", "./plugins/good")));

        assertThat(SkillPackageLayout.declaredPluginSources(marketplace))
                .containsExactly("plugins/good");
    }

    @Test
    void manifestsThatAreNotObjectsOrLackPluginsDeclareNothing() {
        assertThat(SkillPackageLayout.declaredPluginSources(null)).isEmpty();
        assertThat(SkillPackageLayout.declaredPluginSources(Map.of("plugins", "nope"))).isEmpty();
        assertThat(SkillPackageLayout.declaredPluginSources(Map.of("plugins", List.of("x"))))
                .isEmpty();
    }

    @Test
    void candidateListIsBounded() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < SkillPackageLayout.MAX_CANDIDATES + 20; i++) {
            paths.add(String.format("skills/s%03d/SKILL.md", i));
        }

        assertThat(SkillPackageLayout.discover(paths, List.of()))
                .hasSize(SkillPackageLayout.MAX_CANDIDATES);
    }

    // ---- subdirectory selection -------------------------------------------

    @Test
    void subPathIsNormalizedAndBlankMeansRoot() {
        assertThat(SkillPackageLayout.normalizeSubPath(null)).isEmpty();
        assertThat(SkillPackageLayout.normalizeSubPath("   ")).isEmpty();
        assertThat(SkillPackageLayout.normalizeSubPath("/")).isEmpty();
        assertThat(SkillPackageLayout.normalizeSubPath("./")).isEmpty();
        assertThat(SkillPackageLayout.normalizeSubPath("./skills/brainstorming"))
                .isEqualTo("skills/brainstorming");
        assertThat(SkillPackageLayout.normalizeSubPath("skills/brainstorming/"))
                .isEqualTo("skills/brainstorming");
        assertThat(SkillPackageLayout.normalizeSubPath("skills\\brainstorming"))
                .isEqualTo("skills/brainstorming");
    }

    @Test
    void subPathFailsClosedOnEscapesAndHiddenTargets() {
        assertThatThrownBy(() -> SkillPackageLayout.normalizeSubPath("/etc/passwd"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("repository-relative");
        assertThatThrownBy(() -> SkillPackageLayout.normalizeSubPath("C:/windows"))
                .isInstanceOf(SkillImportException.class);
        assertThatThrownBy(() -> SkillPackageLayout.normalizeSubPath("../outside"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("invalid segment");
        assertThatThrownBy(() -> SkillPackageLayout.normalizeSubPath("skills//a"))
                .isInstanceOf(SkillImportException.class);
        assertThatThrownBy(() -> SkillPackageLayout.normalizeSubPath(".claude-plugin/plugin"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("hidden");
    }

    @Test
    void sliceStripsTheSelectedPrefixAndKeepsFileKinds() {
        List<SkillSourceFile> files = List.of(
                text("README.md"),
                skillMd("skills/brainstorming/SKILL.md"),
                text("skills/brainstorming/visual-companion.md"),
                text("skills/testing/SKILL.md"));

        List<SkillSourceFile> sliced = SkillPackageLayout.slice(files, "skills/brainstorming");

        assertThat(sliced).extracting(SkillSourceFile::relativePath)
                .containsExactly("SKILL.md", "visual-companion.md");
        assertThat(sliced.get(0).kind()).isEqualTo(SkillPackageFile.FileKind.SKILL_MD);
    }

    @Test
    void sliceWithoutSubPathReturnsEverythingAndEmptySelectionFails() {
        List<SkillSourceFile> files = List.of(text("SKILL.md"), text("references/a.md"));

        assertThat(SkillPackageLayout.slice(files, null)).hasSize(2);
        assertThat(SkillPackageLayout.slice(files, "")).hasSize(2);
        assertThatThrownBy(() -> SkillPackageLayout.slice(files, "skills/missing"))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("contains no files");
    }

    private static SkillPackageLayout.SkillRoot rootAt(
            List<SkillPackageLayout.SkillRoot> roots, String path) {
        return roots.stream()
                .filter(r -> r.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Skill root at " + path + " in " + roots));
    }

    private static SkillSourceFile text(String path) {
        return file(path, SkillPackageFile.FileKind.TEXT);
    }

    private static SkillSourceFile skillMd(String path) {
        return file(path, SkillPackageFile.FileKind.SKILL_MD);
    }

    private static SkillSourceFile file(String path, SkillPackageFile.FileKind kind) {
        return new SkillSourceFile(path, path.getBytes(StandardCharsets.UTF_8), kind);
    }
}
