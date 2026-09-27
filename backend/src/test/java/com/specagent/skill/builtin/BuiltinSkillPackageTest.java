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
 * 文件名:BuiltinSkillPackageTest.java
 *
 * 测试目标:验证随产品发布的内置 Skill 包的内容契约。
 *
 * 这些包是数据而不是代码,一旦某个包过期,其他地方不会报错:目录改名、
 * 引用缺失或描述超长只会表现为"该 Skill 静默地不再出现"。这个测试就是
 * 真正会先崩溃的那道关卡。
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
            // 目录名就是 BUILTIN 来源的身份标识,若不一致会导致重启时
            // 把同一个 Skill 在第二行重复播种。
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
        // readIndex 只读 classpath 资源,因此不需要任何持久化协作对象即可验证。
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
