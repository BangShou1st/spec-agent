package com.specagent.agent.contract;

import java.util.Locale;

/**
 * A Skill the user explicitly bound to a graph node via the "/" skill picker.
 *
 * <p>Unlike catalog entries (model-autonomous selection), this is a user
 * directive: the decision cycle must activate it before other work. The
 * builder only sets it when the bound skill id is present in the discovered
 * enabled catalog, so a disabled or removed skill never reaches the model.
 */
public record UserRequiredSkillView(String skillId, String name) {

    public UserRequiredSkillView {
        skillId = skillId == null ? "" : skillId.strip().toLowerCase(Locale.ROOT);
        name = name == null ? "" : name;
    }
}
