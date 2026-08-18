package com.specagent.api.agent;

import com.specagent.patch.AnswerPatch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe representation of a persisted answer patch.
 *
 * <p>An answer patch is runtime-derived requirement-state content grounded on
 * the real answered node and answer; it is never a model draft and never a
 * client-supplied object.
 */
public record AnswerPatchResponse(
        UUID id,
        UUID projectId,
        UUID routeId,
        UUID sourceNodeId,
        UUID sourceAnswerId,
        List<ClaimResponse> claims,
        Instant createdAt) {

    public static AnswerPatchResponse from(AnswerPatch patch) {
        return new AnswerPatchResponse(
                patch.id(),
                patch.projectId(),
                patch.routeId(),
                patch.sourceNodeId(),
                patch.sourceAnswerId(),
                patch.claims().stream().map(ClaimResponse::from).toList(),
                patch.createdAt());
    }
}