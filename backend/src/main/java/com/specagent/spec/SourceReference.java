package com.specagent.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * A provenance pointer from a spec claim to a runtime record.
 *
 * <p>Confirmed claims must carry at least one source reference so the spec is
 * traceable to nodes, answers, patches, context snapshots, or routes.
 */
public class SourceReference {

    private final SourceKind kind;
    private final UUID refId;

    @JsonCreator
    public SourceReference(@JsonProperty("kind") SourceKind kind,
                           @JsonProperty("refId") UUID refId) {
        this.kind = kind;
        this.refId = refId;
    }

    public static SourceReference of(SourceKind kind, UUID refId) {
        return new SourceReference(kind, refId);
    }

    @JsonProperty("kind")
    public SourceKind kind() {
        return kind;
    }

    @JsonProperty("refId")
    public UUID refId() {
        return refId;
    }
}
