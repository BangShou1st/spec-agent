package com.specagent.agent.contract;

import java.util.List;

/**
 * One bounded, model-facing Skill catalog entry in the frozen input snapshot.
 * Mirrors the discovery projection: identity + bounded metadata only — never
 * full SKILL.md, filesystem paths, embedding scores, or DB internals.
 */
public record AvailableSkillView(String skillId,
                                 String name,
                                 String description,
                                 String compatibilityHint) {

    public AvailableSkillView {
        description = description == null ? "" : description;
    }
}
