package com.specagent.api.spec;

import com.specagent.spec.SourceReference;

import java.util.UUID;

/**
 * Read-only provenance pointer from a spec claim to a runtime record.
 */
public record SourceReferenceResponse(
        String kind,
        UUID refId) {

    public static SourceReferenceResponse from(SourceReference reference) {
        return new SourceReferenceResponse(reference.kind().code(), reference.refId());
    }
}