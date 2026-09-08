package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Owner of <em>deterministic</em> Skill eligibility: installed → enabled →
 * compatible with the current context's structured facts. It never ranks
 * natural-language relevance — that is the {@link SkillCandidateRetriever}'s
 * job (pass-through in the small-catalog first version).
 */
@Component
public class SkillVisibilityService {

    private final SkillProperties properties;

    public SkillVisibilityService(SkillProperties properties) {
        this.properties = properties;
    }

    /**
     * Deterministic eligibility filter. Currently: enabled Skills only, with
     * optional resource-kind compatibility hints resolved by the caller
     * (retriever/projector layer); always bounded by the catalog limit so the
     * projection never exposes an unbounded list.
     */
    public List<SkillCatalogEntry> eligible(List<SkillCatalogEntry> all,
                                           SkillDiscoveryContext context) {
        return all.stream()
                .filter(SkillCatalogEntry::enabled)
                .limit(properties.getMaxVisible())
                .toList();
    }
}