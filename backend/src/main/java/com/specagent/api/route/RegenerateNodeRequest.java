package com.specagent.api.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Model-powered question replacement request.
 *
 * <p>Normal clients supply only the user instruction. The replacement node
 * content is proposed by the generic DRAFT_NODE model contract. Runtime-owned fields —
 * replacementNodeId, replacementRouteId, optionId, contextSnapshotId, source
 * refs, provenance, createdByRunId, lifecycle status, supersedes ids — are
 * never accepted.
 */
public record RegenerateNodeRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 2000, message = "must be at most 2000 characters")
        String instruction,
        /** Deprecated compatibility-only field; normal product UI never sends it. */
        @Deprecated
        String replacementQuestion,
        /** Deprecated compatibility-only field; normal product UI never sends it. */
        @Deprecated
        @Size(max = 4000, message = "must be at most 4000 characters")
        String replacementPurpose,
        /** Deprecated compatibility-only field; normal product UI never sends it. */
        @Deprecated
        @Valid
        List<ReplacementOptionRequest> replacementOptions) {

}
