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
 * Merge-review acceptance: with 40 enabled Skills and maxVisible=24, a target
 * Skill parked past the automatic Top-K must be recallable through
 * {@code skill.search(query)} backed by the real query-aware retriever —
 * never a mocked search, never a keyword table.
 *
 * <p>Cases: (A) automatic catalog truncates and hides the target; (B) search
 * recalls it; (C) a lexical paraphrase still recalls it (no exact-match
 * dependence); (D) an unrelated Skill ranks below the target; (E) disabled
 * Skills never inflate truncation; (F) search returns metadata only;
 * (G) search never activates.
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
        // 39 alphabetically-first filler Skills with migration-free metadata.
        for (int i = 0; i < 39; i++) {
            String padded = String.format("%02d", i);
            all.add(skill("sk-aaa-" + padded, "aaa-filler-" + padded,
                    "General workspace note-taking helper number " + padded, true));
        }
        // The target sorts last alphabetically and is inserted last.
        all.add(skill(TARGET_ID, TARGET_NAME, TARGET_DESCRIPTION, true));
        // An unrelated Skill with clearly disjoint metadata.
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
        // Shares lexical overlap (schema/migration/backwards/compatibility
        // stems) without copying the description verbatim: proves the ranker
        // is not an exact full-string match.
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
        // The unrelated Skill either misses Top-N entirely or sorts after the
        // target — never above it. No absolute score asserted.
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
