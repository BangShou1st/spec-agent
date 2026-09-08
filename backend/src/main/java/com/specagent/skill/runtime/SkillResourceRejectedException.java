package com.specagent.skill.runtime;

/**
 * Typed failure for a rejected Skill resource read (traversal, oversize,
 * missing, or binary-in-phase-one). Never exposes provider/stack details.
 */
public class SkillResourceRejectedException extends RuntimeException {

    public SkillResourceRejectedException(String message) {
        super(message);
    }
}