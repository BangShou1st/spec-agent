package com.specagent.spec;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists generated spec snapshots.
 *
 * <p>A spec snapshot is a derived artifact for one route tip and one context
 * snapshot. It is not source of truth. Confirmed spec claims must carry source
 * references, which this service records and exposes for verification.
 */
@Service
public class SpecSnapshotService {

    private final SpecSnapshotRepository specSnapshotRepository;

    public SpecSnapshotService(SpecSnapshotRepository specSnapshotRepository) {
        this.specSnapshotRepository = specSnapshotRepository;
    }

    public SpecSnapshot createSnapshot(UUID projectId,
                                       UUID routeId,
                                       UUID tipNodeId,
                                       UUID contextSnapshotId,
                                       String format,
                                       List<SpecSection> sections,
                                       List<UnresolvedItem> unresolvedItems,
                                       List<SourceReference> sourceRefs,
                                       UUID createdByRunId) {
        UUID snapshotId = Ids.random();
        Instant now = Instant.now();
        SpecSnapshot snapshot = new SpecSnapshot(snapshotId, projectId, routeId, tipNodeId,
                contextSnapshotId, format == null ? "markdown" : format,
                sections, unresolvedItems, sourceRefs, createdByRunId, now);
        specSnapshotRepository.save(snapshot);
        return snapshot;
    }

    public java.util.Optional<SpecSnapshot> getSnapshot(UUID snapshotId) {
        return specSnapshotRepository.findById(snapshotId);
    }

    public List<SpecSnapshot> listByRoute(UUID routeId) {
        return specSnapshotRepository.findByRoute(routeId);
    }
}
