package com.specagent.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A section of a generated spec snapshot.
 *
 * <p>Sections are derived output. Confirmed content must remain traceable through
 * the snapshot's source references.
 */
public class SpecSection {

    private final String id;
    private final String title;
    private final String content;

    @JsonCreator
    public SpecSection(@JsonProperty("id") String id,
                       @JsonProperty("title") String title,
                       @JsonProperty("content") String content) {
        this.id = id;
        this.title = title;
        this.content = content;
    }

    public static SpecSection of(String title, String content) {
        return new SpecSection(java.util.UUID.randomUUID().toString(), title, content);
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }

    @JsonProperty("content")
    public String content() {
        return content;
    }
}
