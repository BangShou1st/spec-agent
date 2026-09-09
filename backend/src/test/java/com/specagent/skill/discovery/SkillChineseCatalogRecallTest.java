package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.registry.SkillQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Chinese large-catalog acceptance: 40 enabled Skills, maxVisible=24, the
 * Chinese target parked outside the automatic Top-K must be recallable via
 * {@code skill.search(中文查询)} through the real Unicode-aware retriever.
 * Covers the core gate (target hidden → truncated → search recalls),
 * a lexical paraphrase, both negative-control directions, and a
 * mixed-language query. English regression lives in
 * {@link SkillLargeCatalogRecallTest} and must stay green untouched.
 */
class SkillChineseCatalogRecallTest {

    private static final String TARGET_ID = "sk-zzz-cn-migration";
    private static final String TARGET_NAME = "zzz-数据库迁移安全";
    private static final String TARGET_DESCRIPTION =
            "检查数据库迁移的向后兼容性和破坏性变更";

    private static final String UNRELATED_ID = "sk-cn-a11y";
    private static final String UNRELATED_NAME = "前端无障碍评审";
    private static final String UNRELATED_DESCRIPTION =
            "检查界面键盘导航、颜色对比度和可访问性问题";

    private final SkillProperties properties = SkillProperties.defaults();
    private final SkillQueryService queryService = mock(SkillQueryService.class);

    private SkillDiscoveryService service() {
        properties.setMaxVisible(24);
        return new SkillDiscoveryService(queryService,
                new SkillVisibilityService(),
                new LexicalSkillCandidateRetriever(),
                new SkillCatalogProjector(properties), properties);
    }

    private void givenUniverse() {
        List<Skill> all = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            String padded = String.format("%02d", i);
            all.add(skill("sk-cn-filler-" + padded, "aaa- filler-" + padded,
                    "通用工作区笔记记录助手编号" + padded, true));
        }
        all.add(skill(TARGET_ID, TARGET_NAME, TARGET_DESCRIPTION, true));
        all.add(skill(UNRELATED_ID, UNRELATED_NAME, UNRELATED_DESCRIPTION, true));
        when(queryService.listSkills()).thenReturn(List.copyOf(all));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());
    }

    @Test
    void automaticCatalogHidesChineseTargetAndTruncates() {
        givenUniverse();
        SkillCatalogProjector.Projection projection =
                service().discover(SkillDiscoveryContext.empty());

        assertThat(projection.entries()).hasSizeLessThanOrEqualTo(24);
        assertThat(projection.truncated()).isTrue();
        assertThat(projection.entries()).extracting(SkillCatalogEntry::skillId)
                .doesNotContain(TARGET_ID);
    }

    @Test
    void chineseSearchRecallsHiddenTarget() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("数据库迁移兼容性"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains(TARGET_ID);
    }

    @Test
    void chineseLexicalParaphraseStillRecallsTarget() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("迁移变更兼容检查"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains(TARGET_ID);
    }

    @Test
    void unrelatedChineseSkillRanksBelowTarget() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("数据库迁移兼容性"));

        List<String> ids = results.stream()
                .map(SkillSearchCandidate::skillId).toList();
        assertThat(ids).contains(TARGET_ID);
        int targetRank = ids.indexOf(TARGET_ID);
        int unrelatedRank = ids.indexOf(UNRELATED_ID);
        assertThat(unrelatedRank == -1 || unrelatedRank > targetRank).isTrue();
    }

    @Test
    void reverseQueryRanksAccessibilityAboveMigration() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("前端无障碍键盘导航"));

        List<String> ids = results.stream()
                .map(SkillSearchCandidate::skillId).toList();
        assertThat(ids).contains(UNRELATED_ID);
        int unrelatedRank = ids.indexOf(UNRELATED_ID);
        int targetRank = ids.indexOf(TARGET_ID);
        // Proves ranking follows the query, not a fixed skill position.
        assertThat(targetRank == -1 || unrelatedRank < targetRank).isTrue();
    }

    @Test
    void mixedLanguageQueryScoresBothScripts() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("Postgres 数据库 migration compatibility"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains(TARGET_ID);
    }

    private Skill skill(String skillId, String name, String description, boolean enabled) {
        return new Skill(UUID.randomUUID(), skillId, name, description,
                SkillSourceKind.UPLOAD_ZIP, "source:" + skillId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                enabled, Instant.EPOCH, Instant.EPOCH);
    }
}
