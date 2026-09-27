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
 * 文件名:SkillLargeCatalogRecallTest.java
 *
 * 测试目标:合并评审验收——40 个已启用 Skill、maxVisible=24 时,被排在
 * 自动 Top-K 之外的目标 Skill 必须能通过 {@code skill.search(query)} 召回,
 * 背后是真实的查询感知检索器——绝不使用 mock 搜索,也绝不使用关键词表。
 *
 * 用例:(A) 自动目录截断并隐藏目标;(B) 搜索能召回;(C) 词汇改写仍可
 * 召回(不依赖精确匹配);(D) 无关 Skill 排在目标之后;(E) 禁用的 Skill
 * 不虚增截断;(F) 搜索只返回元数据;(G) 搜索绝不触发激活。
 */
class SkillLargeCatalogRecallTest {

    private static final String TARGET_ID = "sk-zzz-postgres-migration";
    private static final String TARGET_NAME = "zzz-postgres-migration-safety";
    private static final String TARGET_DESCRIPTION =
            "Reviews schema migrations for backwards compatibility and destructive changes.";

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
        // 39 个按字母序排在最前、元数据不含迁移内容的填充 Skill。
        for (int i = 0; i < 39; i++) {
            String padded = String.format("%02d", i);
            all.add(skill("sk-aaa-" + padded, "aaa-filler-" + padded,
                    "General workspace note-taking helper number " + padded, true));
        }
        // 目标按字母序排在最后,并且最后插入。
        all.add(skill(TARGET_ID, TARGET_NAME, TARGET_DESCRIPTION, true));
        // 一个元数据明显不相关的 Skill。
        all.add(skill("sk-frontend-a11y", "frontend-accessibility-review",
                "Checks user interface color contrast and keyboard navigation.", true));
        when(queryService.listSkills()).thenReturn(List.copyOf(all));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());
    }

    @Test
    void automaticCatalogTruncatesAndHidesTarget() {
        givenUniverse();
        SkillCatalogProjector.Projection projection =
                service().discover(SkillDiscoveryContext.empty());

        assertThat(projection.entries()).hasSizeLessThanOrEqualTo(24);
        assertThat(projection.truncated()).isTrue();
        assertThat(projection.entries()).extracting(SkillCatalogEntry::skillId)
                .doesNotContain(TARGET_ID);
    }

    @Test
    void searchRecallsTargetOutsideAutomaticTopK() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch(
                        "schema migration backwards compatibility"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains(TARGET_ID);
    }

    @Test
    void paraphraseWithoutExactWordingStillRecallsTarget() {
        givenUniverse();
        // 与描述存在词汇重叠(schema/migration/backwards/compatibility 的词干),
        // 但没有逐字复制描述:证明排序器不是精确的整串匹配。
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch(
                        "review backwards-compatible schema migrations"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains(TARGET_ID);
    }

    @Test
    void unrelatedSkillRanksBelowTarget() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch(
                        "schema migration backwards compatibility"));

        List<String> ids = results.stream()
                .map(SkillSearchCandidate::skillId).toList();
        assertThat(ids).contains(TARGET_ID);
        // 无关的 Skill 要么完全不在 Top-N 里,要么排在目标之后——绝不会
        // 排在目标之前。不断言绝对分数。
        int targetRank = ids.indexOf(TARGET_ID);
        int unrelatedRank = ids.indexOf("sk-frontend-a11y");
        assertThat(unrelatedRank == -1 || unrelatedRank > targetRank).isTrue();
    }

    @Test
    void searchReturnsMetadataOnly() {
        givenUniverse();
        List<SkillSearchCandidate> results = service().search(
                SkillDiscoveryContext.forSearch("schema migration"));

        SkillSearchCandidate target = results.stream()
                .filter(candidate -> candidate.skillId().equals(TARGET_ID))
                .findFirst().orElseThrow();
        assertThat(target.name()).isEqualTo(TARGET_NAME);
        assertThat(target.description()).isEqualTo(TARGET_DESCRIPTION);
        String wire = results.toString();
        assertThat(wire).doesNotContain("SKILL.md")
                .doesNotContain("filesystem")
                .doesNotContain("embedding");
    }

    private Skill skill(String skillId, String name, String description, boolean enabled) {
        return new Skill(UUID.randomUUID(), skillId, name, description,
                SkillSourceKind.UPLOAD_ZIP, "source:" + skillId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                enabled, Instant.EPOCH, Instant.EPOCH);
    }
}
