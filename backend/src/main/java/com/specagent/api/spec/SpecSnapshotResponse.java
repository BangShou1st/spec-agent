package com.specagent.api.spec;

import com.specagent.spec.SpecSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only spec snapshot representation.
 *
 * <p>A spec snapshot is a derived artifact, never source of truth. The DTO
 * exposes provenance and content only; raw model/provider responses, raw
 * prompts, global requirement state, and database internals are never exposed.
 */
public record SpecSnapshotResponse(
        UUID id,
        UUID projectId,
        UUID routeId,
        UUID tipNodeId,
        UUID contextSnapshotId,
        String format,
        List<SpecSectionResponse> sections,
        List<UnresolvedItemResponse> unresolvedItems,
        List<SourceReferenceResponse> sourceRefs,
        UUID createdByRunId,
        Instant createdAt) {

    public static SpecSnapshotResponse from(SpecSnapshot snapshot) {
        return new SpecSnapshotResponse(
                snapshot.id(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                snapshot.contextSnapshotId(),
                snapshot.format(),
                snapshot.sections().stream().map(SpecSectionResponse::from).toList(),
                snapshot.unresolvedItems().stream().map(UnresolvedItemResponse::from).toList(),
                snapshot.sourceRefs().stream().map(SourceReferenceResponse::from).toList(),
                snapshot.createdByRunId(),
                snapshot.createdAt());
    }
}