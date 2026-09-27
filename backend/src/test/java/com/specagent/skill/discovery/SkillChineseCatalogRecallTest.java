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
 * 文件名:SkillChineseCatalogRecallTest.java
 *
 * 测试目标:中文大目录验收——40 个已启用的 Skill、maxVisible=24 时,
 * 被挤出自动 Top-K 的中文目标 Skill 必须能通过 {@code skill.search(中文查询)}
 * 借助真实的支持 Unicode 的检索器召回。覆盖核心关卡(目标被隐藏 ->
 * 目录截断 -> 搜索召回)、词汇改写、两个方向的阴性对照以及中英混合查询。
 * 英文回归用例见 {@link SkillLargeCatalogRecallTest},须保持原样通过。
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
        // 证明排序跟随查询内容,而不是固定的 Skill 位置。
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
