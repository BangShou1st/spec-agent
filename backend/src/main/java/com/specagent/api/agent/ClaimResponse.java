package com.specagent.api.agent;

import com.specagent.patch.Claim;

import java.util.UUID;

/**
 * Read-only claim inside an answer patch response.
 *
 * <p>Exposes content and runtime-grounded provenance. The runtime-owned claim
 * id is deliberately omitted to keep the API surface minimal; the claim is
 * never treated as a client-writable object.
 */
public record ClaimResponse(
        String kind,
        String text,
        String status,
        Double confidence,
        UUID sourceNodeId,
        UUID sourceAnswerId) {

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }
}