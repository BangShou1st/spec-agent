package com.specagent.readmodel.requirement;

import com.specagent.patch.Claim;

import java.util.UUID;

/**
 * Safe read-model projection of one requirement claim.
 *
 * <p>Exposes content and runtime-grounded provenance only. The runtime-owned
 * claim id, persistence metadata, prompts, and model payloads are never
 * exposed; the claim is derived state, not source of truth.
 */
public record RequirementClaimView(
        String kind,
        String text,
        String status,
        Double confidence,
        UUID sourceNodeId,
        UUID sourceAnswerId) {

    public static RequirementClaimView from(Claim claim) {
        return new RequirementClaimView(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }
}