package com.specagent.agent.contract;

/**
 * Bounded Skill catalog fingerprint carried in the frozen snapshot so replay
 * can verify the model saw the same catalog: stable entry identities plus a
 * catalog fingerprint and the truncation flag.
 */
public record SkillCatalogView(java.util.List<AvailableSkillView> skills,
                               boolean truncated,
                               String fingerprint) {

    public SkillCatalogView {
        skills = skills == null ? java.util.List.of() : java.util.List.copyOf(skills);
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    public static SkillCatalogView empty() {
        return new SkillCatalogView(java.util.List.of(), false, "");
    }
}
