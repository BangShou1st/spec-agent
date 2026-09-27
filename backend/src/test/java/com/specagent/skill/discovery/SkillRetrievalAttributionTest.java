package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SkillRetrievalAttributionTest.java
 *
 * 测试目标:检索失败的归因分层——可见性、检索器、描述符/prompt、
 * 校验器/策略、MCP、归一化各自拥有自己的层。此测试固定小目录首版的
 * 分层契约。
 */
class SkillRetrievalAttributionTest {

    private final SkillProperties properties = SkillProperties.defaults();
    private final SkillVisibilityService visibility = new SkillVisibilityService();
    private final LexicalSkillCandidateRetriever retriever =
            new LexicalSkillCandidateRetriever();
    private final SkillCatalogProjector projector = new SkillCatalogProjector(properties);

    private SkillCatalogEntry entry(String skillId, boolean enabled) {
        return new SkillCatalogEntry(skillId, "n-" + skillId, "desc " + skillId,
                null, "hash-" + skillId, enabled, "UPLOAD_ZIP", null);
    }

    private SkillDiscoveryContext context() {
        return new SkillDiscoveryContext("NORMAL", List.of("RESOURCE:FILE"),
                List.of(), java.util.Map.of());
    }

    @Test
    void ineligibleSkillIsVisibilityFailure() {
        List<SkillCatalogEntry> all = List.of(entry("sk-off", false), entry("sk-on", true));
        List<SkillCatalogEntry> eligible = visibility.eligible(all, context());
        // 正确的 Skill 不可见 -> 归属可见性层。
        assertThat(eligible).extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-on");
    }

    @Test
    void eligibleButAbsentIsRetrieverFailure() {
        List<SkillCatalogEntry> eligible =
                List.of(entry("sk-a", true), entry("sk-b", true), entry("sk-c", true));
        // 符合条件但没进 Top-K -> 归属检索器层。无查询时检索器保持
        // 稳定的目录顺序(Top-2 = 前两个)。
        List<SkillCatalogEntry> topK = retriever.retrieve(context(), eligible, 2);
        assertThat(topK).extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-a", "sk-b");
    }

    @Test
    void visibilityNeverTruncatesEligibleUniverse() {
        // 合并评审结论的回归守卫:可见性层不得预限制为 maxVisible——
        // 完整的符合条件集合必须全部送达检索器,即使超出面向模型的预算。
        var all = new java.util.ArrayList<SkillCatalogEntry>();
        for (int i = 0; i < 40; i++) {
            all.add(entry("sk-" + i, true));
        }
        List<SkillCatalogEntry> eligible = visibility.eligible(all, context());
        assertThat(eligible).hasSize(40);
    }

    @Test
    void projectionIsStableAndFingerprinted() {
        List<SkillCatalogEntry> topK = List.of(entry("sk-a", true), entry("sk-b", true));
        SkillCatalogProjector.Projection first = projector.project(topK, false);
        SkillCatalogProjector.Projection second = projector.project(topK, false);
        // 相同输入 -> 相同顺序、相同指纹:可安全重放。
        assertThat(second.entries()).isEqualTo(first.entries());
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(first.truncated()).isFalse();
    }

    @Test
    void truncationFlagSurvivesProjection() {
        List<SkillCatalogEntry> topK = List.of(entry("sk-a", true));
        SkillCatalogProjector.Projection projection = projector.project(topK, true);
        assertThat(projection.truncated()).isTrue();
    }

    @Test
    void oversizedDescriptionsAreBounded() {
        String huge = "d".repeat(10_000);
        SkillCatalogEntry big = new SkillCatalogEntry("sk-big", "big", huge,
                null, "h", true, "UPLOAD_ZIP", null);
        SkillCatalogProjector.Projection projection =
                projector.project(List.of(big), false);
        assertThat(projection.entries().get(0).description().length())
                .isLessThanOrEqualTo(properties.getMaxDescriptionChars() + 8);
    }
}
