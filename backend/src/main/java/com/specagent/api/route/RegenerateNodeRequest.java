package com.specagent.api.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Deterministic regenerate request.
 *
 * <p>Clients may supply only the user instruction and the replacement node
 * content (question, purpose, option labels/impacts). Runtime-owned fields —
 * replacementNodeId, replacementRouteId, optionId, contextSnapshotId, source
 * refs, provenance, createdByRunId, lifecycle status, supersedes ids — are
 * never accepted.
 */
public record RegenerateNodeRequest(
        @Size(max = 2000, message = "must be at most 2000 characters")
        String instruction,
        @NotBlank(message = "must not be blank")
        @Size(max = 4000, message = "must be at most 4000 characters")
        String replacementQuestion,
        @Size(max = 4000, message = "must be at most 4000 characters")
        String replacementPurpose,
        @Valid
        List<ReplacementOptionRequest> replacementOptions) {
}