package com.specagent.skill.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokenizer regression guard: Unicode-aware generic lexical tokenization.
 * Latin keeps the existing pipeline (lowercase, stop words, minimal
 * stemming); CJK runs emit generic character bigrams; mixed text keeps both.
 * No domain vocabulary anywhere.
 */
class SkillMetadataScorerTokenizeTest {

    @Test
    void englishKeepsExistingBehavior() {
        assertThat(SkillMetadataScorer.tokenize("Reviews schema Migrations!"))
                .contains("review", "schema", "migration");
        // Stop words and short fragments stay filtered.
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
        // "postgres" folds to "postgre" by the pre-existing generic plural
        // rule (trailing-s strip); the point is Latin survives alongside CJK.
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
        // O(n): a run of n CJK chars yields n-1 bigrams, never O(n^2).
        List<String> tokens = SkillMetadataScorer.tokenize("一二三四五六七八九十");
        assertThat(tokens).hasSize(9);
    }
}
