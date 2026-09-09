package com.specagent.skill.discovery;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Owner of <em>deterministic</em> Skill eligibility: installed → enabled →
 * compatible with the current context's structured facts. It never ranks
 * natural-language relevance — that is the {@link SkillCandidateRetriever}'s
 * job — and it never truncates to the model-facing Top-K budget. Truncation
 * is the projector's job, so retrieval always sees the full eligible
 * universe and a future semantic retriever can recall beyond position 24.
 */
@Component
public class SkillVisibilityService {

    /**
     * Deterministic eligibility filter. Currently: enabled Skills only, with
     * optional resource-kind compatibility hints resolved by the caller
     * (retriever/projector layer). Returns ALL eligible Skills unbounded —
     * the retriever + projector own Top-K and model-facing bounds.
     */
    public List<SkillCatalogEntry> eligible(List<SkillCatalogEntry> all,
                                           SkillDiscoveryContext context) {
        return all.stream()
                .filter(SkillCatalogEntry::enabled)
                .toList();
    }
}