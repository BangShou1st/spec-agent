package com.specagent.skill.parser;

import com.specagent.skill.domain.SkillManifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:SkillMarkdownParserTest.java
 *
 * 测试目标:验证 SKILL.md 的解析规则——必需的 name/description、安全的
 * YAML 处理、对 front-matter 的宽容处理,以及格式损坏时返回类型化的失败。
 */
class SkillMarkdownParserTest {

    private final SkillMarkdownParser parser = new SkillMarkdownParser();

    private static final String VALID = """
            ---
            name: test-skill
            description: A skill for testing
            ---
            Step 1: do the thing
            Step 2: verify
            """;

    @Test
    void parsesValidSkillMarkdown() {
        SkillManifest manifest = parser.parse(VALID);

        assertThat(manifest.name()).isEqualTo("test-skill");
        assertThat(manifest.description()).isEqualTo("A skill for testing");
        assertThat(manifest.instructions()).contains("Step 1: do the thing");
    }

    @Test
    void missingNameFailsClosed() {
        assertThatThrownBy(() -> parser.parse("""
                ---
                description: no name here
                ---
                body
                """))
                .isInstanceOf(SkillParseException.class)
                .hasMessageContaining("name");
    }

    @Test
    void missingDescriptionFailsClosed() {
        assertThatThrownBy(() -> parser.parse("""
                ---
                name: only-name
                ---
                body
                """))
                .isInstanceOf(SkillParseException.class)
                .hasMessageContaining("description");
    }

    @Test
    void malformedYamlFailsClosed() {
        assertThatThrownBy(() -> parser.parse("""
                ---
                name: [unclosed
                description: x
                ---
                body
                """))
                .isInstanceOf(SkillParseException.class);
    }

    @Test
    void missingFrontMatterFailsClosed() {
        assertThatThrownBy(() -> parser.parse("plain markdown without front matter"))
                .isInstanceOf(SkillParseException.class)
                .hasMessageContaining("front-matter");
    }

    @Test
    void unclosedFrontMatterFailsClosed() {
        assertThatThrownBy(() -> parser.parse("---\nname: x\ndescription: y\n"))
                .isInstanceOf(SkillParseException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void extraMetadataIsPreservedAsStrings() {
        SkillManifest manifest = parser.parse("""
                ---
                name: meta-skill
                description: carries metadata
                references: refs/example.md
                author: someone
                ---
                body
                """);
        assertThat(manifest.extraMetadata()).containsEntry("author", "someone");
        assertThat(manifest.references()).containsExactly("refs/example.md");
    }

    @Test
    void emptyNameFailsClosed() {
        assertThatThrownBy(() -> parser.parse("""
                ---
                name: ""
                description: desc
                ---
                """))
                .isInstanceOf(SkillParseException.class)
                .hasMessageContaining("name");
    }
}