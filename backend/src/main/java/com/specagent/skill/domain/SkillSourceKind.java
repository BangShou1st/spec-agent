package com.specagent.skill.domain;

/**
 * 文件名:SkillSourceKind.java
 *
 * 用途:Skill 包的来源类型。Skill 是过程性/上下文知识,不是另一个 Agent;
 * 这些类型记录包是怎么获取的,以及之后可以通过什么方式刷新。
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