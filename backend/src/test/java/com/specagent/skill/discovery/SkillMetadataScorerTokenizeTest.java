package com.specagent.skill.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SkillMetadataScorerTokenizeTest.java
 *
 * 测试目标:分词器的回归守卫——支持 Unicode 的通用词法分词。拉丁文
 * 沿用既有管线(小写化、停用词、最小化词干);CJK 连续段落输出通用的
 * 字符二元组;混合文本两者兼得。不包含任何领域专属词汇表。
 */
class SkillMetadataScorerTokenizeTest {

    @Test
    void englishKeepsExistingBehavior() {
        assertThat(SkillMetadataScorer.tokenize("Reviews schema Migrations!"))
                .contains("review", "schema", "migration");
        // 停用词和过短片段保持过滤。
        assertThat(SkillMetadataScorer.tokenize("the and of migration"))
                .containsExactly("migration");
    }

    @Test
    void chineseProducesBigramsNeverEmpty() {
        List<String> tokens = SkillMetadataScorer.tokenize("数据库迁移安全");
        assertThat(tokens).isNotEmpty();
        assertThat(tokens).contains("数据", "据库", "迁移", "安全");
    }

    @Test
    void mixedChineseEnglishKeepsBoth() {
        List<String> tokens =
                SkillMetadataScorer.tokenize("Postgres 数据库 migration 安全");
        // "postgres" 会被既有的通用复数规则(去掉结尾的 s)折叠成
        // "postgre";关键是拉丁文与 CJK 能同时保留。
        assertThat(tokens).contains("postgre", "migration", "数据", "安全");
    }

    @Test
    void punctuationDigitsAndNullAreSafe() {
        assertThat(SkillMetadataScorer.tokenize("v2.0，检查：123")).contains("123");
        assertThat(SkillMetadataScorer.tokenize("")).isEmpty();
        assertThat(SkillMetadataScorer.tokenize("   ")).isEmpty();
        assertThat(SkillMetadataScorer.tokenize(null)).isEmpty();
    }

    @Test
    void tokenizeIsDeterministic() {
        String text = "检查数据库迁移的向后兼容性和破坏性变更 Postgres 123";
        assertThat(SkillMetadataScorer.tokenize(text))
                .isEqualTo(SkillMetadataScorer.tokenize(text));
    }

    @Test
    void cjkBigramCountIsLinear() {
        // O(n):n 个 CJK 字符产生 n-1 个二元组,绝不是 O(n^2)。
        List<String> tokens = SkillMetadataScorer.tokenize("一二三四五六七八九十");
        assertThat(tokens).hasSize(9);
    }
}
