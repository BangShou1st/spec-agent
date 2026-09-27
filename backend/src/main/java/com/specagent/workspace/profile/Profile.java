package com.specagent.workspace.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:Profile.java
 *
 * 用途:通用需求画像(profile)。画像是一种配置,而不是代码:
 * 它定义通用的需求维度与输出偏好,绝不允许引入运行时的领域特定分支。
 */
public class Profile {

    private final UUID id;
    private final String name;
    private final String description;
    private final List<String> aspects;
    private final List<String> specSectionDefinitions;
    private final List<String> questionPolicyHints;
    private final String tone;
    private final Instant createdAt;

    public Profile(UUID id,
                   String name,
                   String description,
                   List<String> aspects,
                   List<String> specSectionDefinitions,
                   List<String> questionPolicyHints,
                   String tone,
                   Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.aspects = aspects == null ? List.of() : List.copyOf(aspects);
        this.specSectionDefinitions = specSectionDefinitions == null ? List.of() : List.copyOf(specSectionDefinitions);
        this.questionPolicyHints = questionPolicyHints == null ? List.of() : List.copyOf(questionPolicyHints);
        this.tone = tone;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<String> aspects() {
        return aspects;
    }

    public List<String> specSectionDefinitions() {
        return specSectionDefinitions;
    }

    public List<String> questionPolicyHints() {
        return questionPolicyHints;
    }

    public String tone() {
        return tone;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
