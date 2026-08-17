package com.specagent.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An item in a spec snapshot that is not yet confirmed.
 *
 * <p>Unsupported or uncertain content must be labeled as assumption, suggestion,
 * risk, or unresolved, never as confirmed.
 */
public class UnresolvedItem {

    private final String text;
    private final String category;

    @JsonCreator
    public UnresolvedItem(@JsonProperty("text") String text,
                          @JsonProperty("category") String category) {
        this.text = text;
        this.category = category;
    }

    public static UnresolvedItem of(String text, String category) {
        return new UnresolvedItem(text, category);
    }

    @JsonProperty("text")
    public String text() {
        return text;
    }

    @JsonProperty("category")
    public String category() {
        return category;
    }
}
