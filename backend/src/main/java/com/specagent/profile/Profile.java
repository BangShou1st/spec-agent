package com.specagent.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Generic requirement profile.
 *
 * <p>A profile is configuration, not code. It defines generic requirement
 * dimensions and output preferences. It must never introduce runtime
 * domain-specific branches.
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
