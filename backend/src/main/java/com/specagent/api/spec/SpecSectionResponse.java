package com.specagent.api.spec;

import com.specagent.spec.SpecSection;

/**
 * Read-only section of a spec snapshot.
 */
public record SpecSectionResponse(
        String id,
        String title,
        String content) {

    public static SpecSectionResponse from(SpecSection section) {
        return new SpecSectionResponse(section.id(), section.title(), section.content());
    }
}