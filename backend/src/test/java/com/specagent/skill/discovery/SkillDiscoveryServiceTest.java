package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillSourceKind;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.registry.SkillQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skill discovery rules: per-fresh-context projection, disabled invisible,
 * bounded catalog + truncated flag, stable ordering and replay fingerprint.
 */
class SkillDiscoveryServiceTest {

    private final SkillProperties properties = SkillProperties.defaults();
    private final SkillQueryService queryService = mock(SkillQueryService.class);
    private final SkillVisibilityService visibility =
            new SkillVisibilityService();
    private final SkillCandidateRetriever retriever =
            new LexicalSkillCandidateRetriever();
    private final SkillCatalogProjector projector = new SkillCatalogProjector(properties);

    private final SkillDiscoveryService service = new SkillDiscoveryService(
            queryService, visibility, retriever, projector, properties);

    @Test
    void disabledSkillsAreInvisible() {
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-a", "Alpha", false, "v1"),
                skill("sk-b", "Beta", true, "v2")));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());

        SkillCatalogProjector.Projection projection =
                service.discover(SkillDiscoveryContext.empty());

        assertThat(projection.entries())
                .extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-b");
        assertThat(projection.entries()).allMatch(SkillCatalogEntry::enabled);
    }

    @Test
    void catalogIsBoundedWithTruncatedFlag() {
        properties.setMaxVisible(2);
        SkillDiscoveryService smallService = new SkillDiscoveryService(
                queryService, visibility, retriever, projector, properties);
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-1", "One", true, "v1"),
                skill("sk-2", "Two", true, "v2"),
                skill("sk-3", "Three", true, "v3")));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());

        SkillCatalogProjector.Projection projection = smallService.discover(
                SkillDiscoveryContext.empty());

        assertThat(projection.entries()).hasSize(2);
        assertThat(projection.truncated()).isTrue();
    }

    @Test
    void sameInputsProduceStableFingerprint() {
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-1", "One", true, "v1"),
                skill("sk-2", "Two", true, "v2")));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());

        SkillCatalogProjector.Projection first = service.discover(SkillDiscoveryContext.empty());
        SkillCatalogProjector.Projection second = service.discover(SkillDiscoveryContext.empty());

        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(second.entries().stream().map(SkillCatalogEntry::skillId).toList())
                .isEqualTo(first.entries().stream().map(SkillCatalogEntry::skillId).toList());
    }

    @Test
    void freshCatalogCanDifferFromPrevious() {
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-a", "Alpha", true, "v1")));
        SkillCatalogProjector.Projection first = service.discover(SkillDiscoveryContext.empty());

        // A fresh decision context may legitimately discover new Skills.
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-a", "Alpha", true, "v1"),
                skill("sk-d", "Delta", true, "v2")));
        SkillCatalogProjector.Projection second = service.discover(SkillDiscoveryContext.empty());

        assertThat(second.entries())
                .extracting(SkillCatalogEntry::skillId)
                .containsExactly("sk-a", "sk-d");
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    @Test
    void searchReturnsMetadataOnlyNeverActivates() {
        when(queryService.listSkills()).thenReturn(List.of(
                skill("sk-a", "Alpha", true, "v1"),
                skill("sk-hidden", "Hidden", false, "v2")));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());

        List<SkillSearchCandidate> results =
                service.search(SkillDiscoveryContext.forSearch("alpha"));

        assertThat(results)
                .extracting(SkillSearchCandidate::skillId)
                .containsExactly("sk-a");
        assertThat(results.get(0).description()).isNotEmpty();
    }

    @Test
    void searchIsQueryAwareOverFullEligibleUniverse() {
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());
        var alpha = skill("sk-a", "aaa-first", true, "v1");
        var target = new Skill(UUID.randomUUID(), "sk-target",
                "zzz-postgres-migration-safety",
                "Reviews schema migrations for backwards compatibility "
                        + "and destructive changes.",
                SkillSourceKind.UPLOAD_ZIP, "source:sk-target",
                markerId("v2"), true, Instant.EPOCH, Instant.EPOCH);
        when(queryService.listSkills()).thenReturn(List.of(alpha, target));

        List<SkillSearchCandidate> results = service.search(
                SkillDiscoveryContext.forSearch("schema migration backwards compatibility"));

        assertThat(results).extracting(SkillSearchCandidate::skillId)
                .contains("sk-target");
        // Query-aware: the migration Skill outranks the alphabetically-first one.
        assertThat(results.get(0).skillId()).isEqualTo("sk-target");
    }

    @Test
    void truncatedReflectsEligibleNotInstalled() {
        properties.setMaxVisible(24);
        var all = new java.util.ArrayList<Skill>();
        for (int i = 0; i < 30; i++) {
            all.add(skill("sk-off-" + i, "Off " + i, false, "v1"));
        }
        for (int i = 0; i < 10; i++) {
            all.add(skill("sk-on-" + i, "On " + i, true, "v2"));
        }
        when(queryService.listSkills()).thenReturn(List.copyOf(all));
        when(queryService.findVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(queryService.listFileSummaries(any(UUID.class))).thenReturn(List.of());

        SkillCatalogProjector.Projection projection =
                service.discover(SkillDiscoveryContext.empty());

        // 40 installed but only 10 eligible: no truncation.
        assertThat(projection.entries()).hasSize(10);
        assertThat(projection.truncated()).isFalse();
    }

    private Skill skill(String skillId, String name, boolean enabled, String versionMarker) {
        return new Skill(UUID.randomUUID(), skillId, name, "description of " + name,
                SkillSourceKind.UPLOAD_ZIP, "source:" + skillId,
                markerId(versionMarker), enabled, Instant.EPOCH, Instant.EPOCH);
    }

    private UUID markerId(String marker) {
        return switch (marker) {
            case "v1" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
            case "v2" -> UUID.fromString("00000000-0000-0000-0000-000000000002");
            case "v3" -> UUID.fromString("00000000-0000-0000-0000-000000000003");
            default -> UUID.randomUUID();
        };
    }
}