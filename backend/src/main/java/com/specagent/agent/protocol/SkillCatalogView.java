package com.specagent.agent.protocol;

/**
 * Bounded Skill catalog fingerprint carried in the frozen snapshot so replay
 * can verify the model saw the same catalog: stable entry identities plus a
 * catalog fingerprint and the truncation flag. {@code userRequired} carries
 * the user's explicit skill directive for this context when one exists.
 */
public record SkillCatalogView(java.util.List<AvailableSkillView> skills,
                               boolean truncated,
                               String fingerprint,
                               UserRequiredSkillView userRequired) {

    public SkillCatalogView {
        skills = skills == null ? java.util.List.of() : java.util.List.copyOf(skills);
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    /** Legacy constructor for callers that predate the user directive field. */
    public SkillCatalogView(java.util.List<AvailableSkillView> skills,
                            boolean truncated,
                            String fingerprint) {
        this(skills, truncated, fingerprint, null);
    }

    public static SkillCatalogView empty() {
        return new SkillCatalogView(java.util.List.of(), false, "", null);
    }
}
