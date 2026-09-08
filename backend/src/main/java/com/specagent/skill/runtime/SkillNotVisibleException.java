package com.specagent.skill.runtime;

/**
 * Typed failure for a Skill that exists but is not visible/activatable
 * (unknown, disabled, or not installed). Surfaces a clean message to the
 * caller — never a raw provider/stack detail.
 */
public class SkillNotVisibleException extends RuntimeException {

    public SkillNotVisibleException(String skillId) {
        super("Skill is not visible or not activated: " + skillId);
    }
}