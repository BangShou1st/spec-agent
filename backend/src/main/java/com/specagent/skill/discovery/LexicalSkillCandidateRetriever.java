package com.specagent.skill.discovery;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Query-aware lexical retriever: when the discovery context carries a search
 * query, ranks the full eligible universe by generic metadata relevance and
 * returns the bounded Top-K; without a query it preserves stable catalog
 * order (insertion/registration order) for the automatic projection.
 *
 * <p>One retrieval abstraction serves both the automatic catalog and the
 * {@code skill.search} fallback — search never reimplements ranking. The
 * scorer is domain-blind (no keyword tables, no synonyms, no provider
 * routing); relevance is pure query↔metadata token affinity.
 */
@Component
public class LexicalSkillCandidateRetriever implements SkillCandidateRetriever {

    @Override
    public List<SkillCatalogEntry> retrieve(SkillDiscoveryContext context,
                                            List<SkillCatalogEntry> eligible,
                                            int limit) {
        if (eligible.isEmpty() || limit <= 0) {
            return limit <= 0 ? List.of() : List.copyOf(eligible);
        }
        String query = context == null ? null : context.searchQuery();
        List<SkillCatalogEntry> ordered = (query == null || query.isBlank())
                ? List.copyOf(eligible)
                : SkillMetadataScorer.rankByQuery(query, eligible);
        return ordered.stream().limit(limit).toList();
    }
}
