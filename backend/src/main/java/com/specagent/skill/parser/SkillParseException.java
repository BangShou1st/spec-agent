package com.specagent.skill.parser;

/**
 * Typed failure for malformed or invalid Skill package parsing.
 * Never leaks provider/stack details to the model or user.
 */
public class SkillParseException extends RuntimeException {

    public SkillParseException(String message) {
        super(message);
    }
}