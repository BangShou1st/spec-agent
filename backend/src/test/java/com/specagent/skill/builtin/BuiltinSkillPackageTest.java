package com.specagent.skill.builtin;

import com.specagent.skill.domain.SkillManifest;
import com.specagent.skill.parser.SkillMarkdownParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content contract for the shipped built-in Skill packages.
 *
 * <p>These packages are data, not code, so nothing else fails when one of them
 * goes stale: a renamed folder, a missing reference or an over-long
 * description would only surface as "that skill silently never appears".
 * This test is the thing that actually breaks.
 */
class BuiltinSkillPackageTest {

    private static final SkillMarkdownParser PARSER = new SkillMarkdownParser();
    private static final String ROOT = "builtin-skills/";
    private static final int MAX_DESCRIPTION_CHARS = 320;

    @Test
    void every_indexed_package_has_a_parseable_skill_md_matching_its_folder_name() {
        Map<String, List<String>> packages = readIndex();
        assertThat(packages).as("built-in skill index must not be empty").isNotEmpty();

        for (Map.Entry<String, List<String>> entry : packages.entrySet()) {
            String packageName = entry.getKey();
            SkillManifest manifest = PARSER.parse(readText(ROOT + packageName + "/SKILL.md"));

            assertThat(manifest.hasValidName())
                    .as("SKILL.md name must satisfy the package name pattern: %s", manifest.name())
                    .isTrue();
            // The folder name is the BUILTIN source identity, so a mismatch
            // would make restarts re-seed the same skill under a second row.
            assertThat(manifest.name())
                    .as("SKILL.md name must equal its folder name in %s", packageName)
                    .isEqualTo(packageName);
            assertThat(manifest.description())
                    .as("description is shown in the catalog and must stay short: %s", packageName)
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(MAX_DESCRIPTION_CHARS);
            assertThat(manifest.instructions())
                    .as("instructions must carry the actual procedure: %s", packageName)
                    .isNotBlank();
        }
    }

    @Test
    void every_declared_reference_is_shipped_inside_its_package() {
        for (Map.Entry<String, List<String>> entry : readIndex().entrySet()) {
            String packageName = entry.getKey();
            SkillManifest manifest = PARSER.parse(readText(ROOT + packageName + "/SKILL.md"));
            for (String reference : manifest.references()) {
                assertThat(new ClassPathResource(ROOT + packageName + "/" + reference).exists())
                        .as("declared reference must be shipped: %s -> %s", packageName, reference)
                        .isTrue();
            }
        }
    }

    @Test
    void every_indexed_file_exists_under_its_package_folder() {
        for (Map.Entry<String, List<String>> entry : readIndex().entrySet()) {
            for (String path : entry.getValue()) {
                assertThat(path).startsWith(entry.getKey() + "/");
                assertThat(new ClassPathResource(ROOT + path).exists())
                        .as("indexed file must exist: %s", path)
                        .isTrue();
            }
        }
    }

    @Test
    void seeder_groups_the_index_by_package_and_keeps_the_skill_md_first() {
        // readIndex only reads classpath resources, so it is exercised without
        // any persistence collaborator.
        Map<String, List<String>> packages = new BuiltinSkillSeeder(null, null).readIndex();

        assertThat(packages).isNotEmpty();
        for (Map.Entry<String, List<String>> entry : packages.entrySet()) {
            String manifestEntry = entry.getKey() + "/SKILL.md";
            assertThat(entry.getValue())
                    .as("every package must be seeded with its SKILL.md: %s", entry.getKey())
                    .contains(manifestEntry);
        }
    }

    private static Map<String, List<String>> readIndex() {
        Map<String, List<String>> packages = new LinkedHashMap<>();
        for (String raw : readText(ROOT + "index.txt").split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int slash = line.indexOf('/');
            assertThat(slash).as("index entry must be <package>/<path>: %s", line).isGreaterThan(0);
            packages.computeIfAbsent(line.substring(0, slash), (key) -> new ArrayList<>()).add(line);
        }
        return packages;
    }

    private static String readText(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        assertThat(resource.exists()).as("missing resource: %s", classpathLocation).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + classpathLocation, ex);
        }
    }
}
