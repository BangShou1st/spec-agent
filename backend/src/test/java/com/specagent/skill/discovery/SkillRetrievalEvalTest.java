package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SkillRetrievalEvalTest.java
 *
 * 测试目标:检索评估基座(确定性、离线)——为小目录首版固定 recall 式
 * 的度量口径,便于后续替换检索器时用同一组关卡衡量。
 *
 * 这里使用的定义:
 * - Visibility recall:已安装目录中符合条件( eligible )的比例。
 * - Retrieval Recall@K:符合条件集合中被检索到的比例。
 * - Catalog cost:有界的条目数 + 有界的元数据字节数。
 */
class SkillRetrievalEvalTest {

    private final SkillProperties properties = SkillProperties.defaults();
    private final SkillVisibilityService visibility = new SkillVisibilityService();
    private final LexicalSkillCandidateRetriever retriever =
            new LexicalSkillCandidateRetriever();
    private final SkillCatalogProjector projector = new SkillCatalogProjector(properties);

    private SkillCatalogEntry entry(String skillId, String description) {
        return new SkillCatalogEntry(skillId, "n-" + skillId, description,
                null, "hash-" + skillId, true, "UPLOAD_ZIP", null);
    }

    @Test
    void visibilityRecallIsCompleteForEnabledCatalog() {
        List<SkillCatalogEntry> all = List.of(
                entry("sk-a", "migration safety"),
                entry("sk-b", "doc review"),
                entry("sk-c", "api design"));
        List<SkillCatalogEntry> eligible = visibility.eligible(all,
                SkillDiscoveryContext.empty());
        double visibilityRecall = (double) eligible.size() / all.size();
        assertThat(visibilityRecall).isEqualTo(1.0);
    }

    @Test
    void retrievalRecallAtKIsCompleteWithinBudget() {
        List<SkillCatalogEntry> eligible = List.of(
                entry("sk-a", "migration safety"),
                entry("sk-b", "doc review"));
        int k = properties.getMaxVisible();
        List<SkillCatalogEntry> retrieved =
                retriever.retrieve(SkillDiscoveryContext.empty(), eligible, k);
        double recallAtK = eligible.isEmpty() ? 1.0
                : (double) retrieved.size() / Math.min(k, eligible.size());
        assertThat(recallAtK).isEqualTo(1.0);
    }

    @Test
    void catalogCostStaysBounded() {
        List<SkillCatalogEntry> eligible = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            eligible.add(entry("sk-" + i, "desc " + i));
        }
        SkillDiscoveryContext context = new SkillDiscoveryContext("NORMAL",
                List.of(), List.of(), Map.of());
        List<SkillCatalogEntry> retrieved =
                retriever.retrieve(context, eligible, properties.getMaxVisible());
        SkillCatalogProjector.Projection projection =
                projector.project(retrieved, eligible.size() > retrieved.size());
        assertThat(projection.entries().size())
                .isLessThanOrEqualTo(properties.getMaxVisible());
        assertThat(projection.truncated()).isTrue();
        int bytes = projection.entries().stream()
                .mapToInt(e -> (e.skillId() + e.name() + e.description()).length())
                .sum();
        assertThat(bytes).isLessThanOrEqualTo(
                properties.getMaxVisible() * properties.getMaxDescriptionChars() * 2);
    }

    @Test
    void searchRateIsZeroWhenCatalogFits() {
        // 小目录绝不截断,因此 skill.search 永远不会暴露,
        // 该关卡的搜索调用率按构造为零。
        List<SkillCatalogEntry> eligible = List.of(entry("sk-a", "x"));
        SkillCatalogProjector.Projection projection =
                projector.project(eligible, false);
        assertThat(projection.truncated()).isFalse();
    }

    @Test
    void largeCatalogSearchRecallAtKContainsTarget() {
        // 为将来替换检索器准备的基线:40 个符合条件、K=10,被排在自动
        // Top-24 之外的目标必须出现在 search 的 Recall@10 中。
        // 这里校验相关性,而不仅仅是 retrieved.size() == K。
        List<SkillCatalogEntry> eligible = new java.util.ArrayList<>();
        for (int i = 0; i < 39; i++) {
            eligible.add(new SkillCatalogEntry("sk-aaa-" + String.format("%02d", i),
                    "aaa-filler-" + i, "General workspace note-taking helper " + i,
                    null, "hash-" + i, true, "UPLOAD_ZIP", null));
        }
        eligible.add(new SkillCatalogEntry("sk-zzz-target", "zzz-schema-migration",
                "Reviews schema migrations for backwards compatibility "
                        + "and destructive changes.",
                null, "hash-target", true, "UPLOAD_ZIP", null));

        int k = 10;
        List<SkillCatalogEntry> retrieved = retriever.retrieve(
                SkillDiscoveryContext.forSearch(
                        "schema migration backwards compatibility"),
                eligible, k);

        assertThat(retrieved).hasSize(k);
        assertThat(retrieved).extracting(SkillCatalogEntry::skillId)
                .contains("sk-zzz-target");
    }
}
