package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval failure attribution: visibility vs retriever vs descriptor/prompt
 * vs validator/policy vs MCP vs normalization each own their layer. This test
 * pins the layering contract for the small-catalog first version.
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
        // Correct Skill not eligible -> Visibility layer owns it.
        assertThat(eligible).extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-on");
    }

    @Test
    void eligibleButAbsentIsRetrieverFailure() {
        List<SkillCatalogEntry> eligible =
                List.of(entry("sk-a", true), entry("sk-b", true), entry("sk-c", true));
        // Eligible but absent from Top-K -> Retriever layer owns it. Without a
        // query the retriever preserves stable catalog order (Top-2 = first two).
        List<SkillCatalogEntry> topK = retriever.retrieve(context(), eligible, 2);
        assertThat(topK).extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-a", "sk-b");
    }

    @Test
    void visibilityNeverTruncatesEligibleUniverse() {
        // Regression guard for the merge-review finding: visibility must not
        // pre-limit to maxVisible — the full eligible universe reaches the
        // retriever even when it exceeds the model-facing budget.
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
        // Same inputs -> same order, same fingerprint: replay-safe.
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
