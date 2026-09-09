package com.specagent.skill.domain;

/**
 * Skill package source kind. A Skill is procedural/context knowledge, not
 * another agent; these kinds determine how a package was obtained and how it
 * may be refreshed.
 */
public enum SkillSourceKind {

    BUILTIN("BUILTIN"),
    UPLOAD_ZIP("UPLOAD_ZIP"),
    GIT_HTTPS("GIT_HTTPS");

    private final String code;

    SkillSourceKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SkillSourceKind fromCode(String code) {
        for (SkillSourceKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown skill source kind: " + code);
    }
}