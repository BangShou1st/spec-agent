package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval evaluation harness (deterministic, offline): pins recall-style
 * accounting for the small-catalog first version so later retriever swaps can
 * be measured against the same gates.
 *
 * <p>Definitions used here:
 * <ul>
 *   <li>Visibility recall: eligible fraction of the installed catalog.</li>
 *   <li>Retrieval Recall@K: retrieved fraction of the eligible set.</li>
 *   <li>Catalog cost: bounded entry count + bounded metadata bytes.</li>
 * </ul>
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
        // Small catalogs never truncate, so skill.search is never exposed and
        // the search invocation rate for this gate is zero by construction.
        List<SkillCatalogEntry> eligible = List.of(entry("sk-a", "x"));
        SkillCatalogProjector.Projection projection =
                projector.project(eligible, false);
        assertThat(projection.truncated()).isFalse();
    }

    @Test
    void largeCatalogSearchRecallAtKContainsTarget() {
        // Baseline for future retriever swaps: 40 eligible, K=10, the target
        // parked outside the automatic Top-24 must appear in search Recall@10.
        // Gates relevance, not just retrieved.size() == K.
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
