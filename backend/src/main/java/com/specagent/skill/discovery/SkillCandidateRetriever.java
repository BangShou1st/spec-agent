package com.specagent.skill.discovery;

import java.util.List;

/**
 * Semantic candidate reduction/ranking for Skill discovery. The first
 * implementation is a pass-through for small catalogs; a replaceable
 * implementation may later add lexical/semantic retrieval behind this same
 * narrow contract. It never grants permission, activates Skills, or mutates
 * anything — the model remains the final semantic selector.
 */
public interface SkillCandidateRetriever {

    /**
     * Reduces eligible candidates to the bounded Top-K most relevant to the
     * query. Implementations must be deterministic for the same inputs.
     */
    List<SkillCatalogEntry> retrieve(SkillDiscoveryContext context,
                                     List<SkillCatalogEntry> eligible,
                                     int limit);
}