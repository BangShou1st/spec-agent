package com.specagent.skill.discovery;

import com.specagent.skill.config.SkillProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pass-through retriever for small catalogs: no ranking, no relevance model.
 * The bounded catalog limit alone controls exposure. When catalog growth and
 * evaluation show recall degradation, replace this implementation (same
 * interface) with lexical/semantic retrieval.
 */
@Component
public class PassThroughSkillCandidateRetriever implements SkillCandidateRetriever {

    private final SkillProperties properties;

    public PassThroughSkillCandidateRetriever(SkillProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SkillCatalogEntry> retrieve(SkillDiscoveryContext context,
                                            List<SkillCatalogEntry> eligible,
                                            int limit) {
        if (limit <= 0) {
            limit = properties.getMaxVisible();
        }
        return eligible.stream().limit(limit).toList();
    }
}