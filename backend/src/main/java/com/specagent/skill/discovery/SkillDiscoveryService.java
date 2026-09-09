package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent-facing facade for Skill discovery. Context construction consumes this
 * single entry point; Agent code never knows which visibility/retrieval/
 * projector implementations are behind it.
 *
 * <p>Discovery runs per fresh Decision context: the same frozen snapshot
 * replays the identical projection (the catalog is derived from the immutable
 * installed-versions state and frozen inputs), while a fresh continuation
 * snapshot may legitimately produce a different catalog.
 */
@Service
public class SkillDiscoveryService {

    private final SkillQueryService queryService;
    private final SkillVisibilityService visibilityService;
    private final SkillCandidateRetriever retriever;
    private final SkillCatalogProjector projector;
    private final SkillProperties properties;

    public SkillDiscoveryService(SkillQueryService queryService,
                                 SkillVisibilityService visibilityService,
                                 SkillCandidateRetriever retriever,
                                 SkillCatalogProjector projector,
                                 SkillProperties properties) {
        this.queryService = queryService;
        this.visibilityService = visibilityService;
        this.retriever = retriever;
        this.projector = projector;
        this.properties = properties;
    }

    /**
     * Builds the bounded model-facing Skill catalog for one discovery context.
     * The full eligible universe flows into the retriever; the projector owns
     * the model-facing Top-K bound, so {@code truncated} reflects eligible
     * vs projected — never installed-vs-visible.
     */
    public SkillCatalogProjector.Projection discover(SkillDiscoveryContext context) {
        List<SkillCatalogEntry> all = queryService.listSkills().stream()
                .map(this::toCatalogEntry)
                .toList();
        List<SkillCatalogEntry> eligible = visibilityService.eligible(all, context);
        List<SkillCatalogEntry> topK = retriever.retrieve(context, eligible,
                properties.getMaxVisible());
        boolean truncated = eligible.size() > topK.size();
        return projector.project(topK, truncated);
    }

    /**
     * {@code skill.search}: query-aware metadata candidates over the FULL
     * eligible universe — the point of the fallback is recalling Skills the
     * automatic Top-K did not show. Never activates anything; the model makes
     * the final activation decision. Metadata only (no SKILL.md bodies,
     * resource content, scripts, paths, or DB internals).
     */
    public List<SkillSearchCandidate> search(SkillDiscoveryContext context) {
        List<SkillCatalogEntry> all = queryService.listSkills().stream()
                .map(this::toCatalogEntry)
                .toList();
        List<SkillCatalogEntry> eligible = visibilityService.eligible(all, context);
        List<SkillCatalogEntry> ranked = retriever.retrieve(context, eligible,
                properties.getSearchMaxResults());
        return ranked.stream()
                .map(entry -> new SkillSearchCandidate(
                        entry.skillId(), entry.name(), entry.description(),
                        entry.compatibilityHint()))
                .toList();
    }

    private SkillCatalogEntry toCatalogEntry(Skill skill) {
        Optional<SkillVersion> version = skill.currentVersionId() == null
                ? Optional.empty() : queryService.findVersion(skill.currentVersionId());
        SkillCatalogEntry entry = SkillCatalogEntry.from(skill, version.orElse(null));
        // Bounded compatibility hint from resource kinds the package declares
        // (metadata references), resolved without loading full content.
        String hint = compatibilityHint(entry);
        return hint == null ? entry : entry.withCompatibilityHint(hint);
    }

    private String compatibilityHint(SkillCatalogEntry entry) {
        if (entry.versionId() == null) {
            return null;
        }
        List<String> refs = queryService.listFileSummaries(UUID.fromString(entry.versionId()))
                .stream()
                .map(summary -> summary.relativePath())
                // SKILL.md itself is the instruction body, not a hint; only
                // sibling resource/reference files describe compatibility.
                .filter(path -> !"SKILL.md".equals(path))
                .filter(path -> path.toLowerCase()
                        .matches(".*\\.(pdf|docx|xlsx|csv|json|sql|md|txt)$"))
                .limit(4)
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .toList();
        return refs.isEmpty() ? null : String.join(", ", refs);
    }
}