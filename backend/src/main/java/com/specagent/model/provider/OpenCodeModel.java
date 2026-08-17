package com.specagent.model.provider;

/**
 * One entry of the OpenCode Zen {@code GET /models} payload.
 */
public record OpenCodeModel(String id, String ownedBy) {

    public OpenCodeModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("model id is required");
        }
    }
}