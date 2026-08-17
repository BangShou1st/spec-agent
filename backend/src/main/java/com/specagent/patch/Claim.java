package com.specagent.patch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specagent.common.Ids;

import java.util.UUID;

/**
 * Domain-neutral requirement claim derived from an answer.
 *
 * <p>A claim is a structured piece of requirement state. It is always traceable
 * to the node and answer that produced it, and (when confirmed) to a source
 * reference. Claims are the unit replayed to build {@code RequirementState}.
 */
public class Claim {

    private final UUID id;
    private final ClaimKind kind;
    private final String text;
    private final ClaimStatus status;
    private final Double confidence;
    private final UUID sourceNodeId;
    private final UUID sourceAnswerId;

    @JsonCreator
    public Claim(@JsonProperty("id") UUID id,
                 @JsonProperty("kind") ClaimKind kind,
                 @JsonProperty("text") String text,
                 @JsonProperty("status") ClaimStatus status,
                 @JsonProperty("confidence") Double confidence,
                 @JsonProperty("sourceNodeId") UUID sourceNodeId,
                 @JsonProperty("sourceAnswerId") UUID sourceAnswerId) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.status = status;
        this.confidence = confidence;
        this.sourceNodeId = sourceNodeId;
        this.sourceAnswerId = sourceAnswerId;
    }

    public static Claim of(ClaimKind kind, String text, ClaimStatus status, UUID sourceNodeId, UUID sourceAnswerId) {
        return new Claim(Ids.random(), kind, text, status, null, sourceNodeId, sourceAnswerId);
    }

    public Claim withId(UUID newId) {
        return new Claim(newId, kind, text, status, confidence, sourceNodeId, sourceAnswerId);
    }

    @JsonProperty("id")
    public UUID id() {
        return id;
    }

    @JsonProperty("kind")
    public ClaimKind kind() {
        return kind;
    }

    @JsonProperty("text")
    public String text() {
        return text;
    }

    @JsonProperty("status")
    public ClaimStatus status() {
        return status;
    }

    @JsonProperty("confidence")
    public Double confidence() {
        return confidence;
    }

    @JsonProperty("sourceNodeId")
    public UUID sourceNodeId() {
        return sourceNodeId;
    }

    @JsonProperty("sourceAnswerId")
    public UUID sourceAnswerId() {
        return sourceAnswerId;
    }

    public boolean isConfirmed() {
        return status == ClaimStatus.CONFIRMED;
    }
}
