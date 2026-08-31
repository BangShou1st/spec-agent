package com.specagent.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructor normalization regression tests. Every list field must tolerate a
 * null argument (normalized to an empty list); {@code includedPatchIds} once
 * regressed into a {@code List.copyOf(null)} NPE in both branches.
 */
class ContextSnapshotTest {

    private ContextSnapshot snapshotWithNullLists() {
        return new ContextSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                ContextOperationType.NODE_QUERY,
                null, null, null, null, null, null,
                null, "hash-1", Instant.now());
    }

    @Test
    void nullListArgumentsNormalizeToEmptyLists() {
        ContextSnapshot snapshot = snapshotWithNullLists();
        assertThat(snapshot.includedNodeIds()).isEmpty();
        assertThat(snapshot.includedAnswerIds()).isEmpty();
        assertThat(snapshot.includedPatchIds()).isEmpty();
        assertThat(snapshot.excludedRouteIds()).isEmpty();
        assertThat(snapshot.relatedNodeIds()).isEmpty();
        assertThat(snapshot.relations()).isEmpty();
    }

    @Test
    void presentListsAreDefensivelyCopiedAndUnmodifiable() {
        UUID nodeA = UUID.randomUUID();
        List<UUID> nodes = new java.util.ArrayList<>(List.of(nodeA));
        List<ContextRelation> relations = new java.util.ArrayList<>(List.of(
                new ContextRelation(nodeA, UUID.randomUUID(), "DEPENDS_ON")));

        ContextSnapshot snapshot = new ContextSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), nodeA,
                ContextOperationType.NODE_QUERY,
                nodes, List.of(), List.of(), List.of(),
                List.of(nodeA), relations,
                null, "hash-2", Instant.now());

        // Mutating the caller's list must not leak into the snapshot.
        nodes.add(UUID.randomUUID());
        relations.clear();
        assertThat(snapshot.includedNodeIds()).containsExactly(nodeA);
        assertThat(snapshot.relations()).hasSize(1);
        assertThat(snapshot.relatedNodeIds()).containsExactly(nodeA);
        assertThat(snapshot.includedNodeIds()).isUnmodifiable();
        assertThat(snapshot.relations()).isUnmodifiable();
    }
}