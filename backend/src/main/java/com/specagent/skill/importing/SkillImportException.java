package com.specagent.skill.importing;

/**
 * Typed failure for Skill import/validation/install problems. Expected
 * failures (unsafe archives, bad git refs, oversized packages) surface as
 * this typed exception so the API can present a clean message — raw provider
 * exceptions never reach the model or user.
 */
public class SkillImportException extends RuntimeException {

    public SkillImportException(String message) {
        super(message);
    }

    public SkillImportException(String message, Throwable cause) {
        super(message, cause);
    }
}